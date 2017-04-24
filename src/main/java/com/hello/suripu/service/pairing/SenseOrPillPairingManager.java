package com.hello.suripu.service.pairing;

import com.amazonaws.AmazonServiceException;
import com.google.common.base.Optional;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.alerts.Alert;
import com.hello.suripu.core.alerts.AlertsDAO;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.service.pairing.pill.PillPairStateEvaluator;
import com.hello.suripu.service.pairing.pill.PillPairingRequest;
import com.hello.suripu.service.pairing.sense.SensePairStateEvaluator;
import com.hello.suripu.service.pairing.sense.SensePairingRequest;
import com.hello.suripu.service.utils.RegistrationLogger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.List;

public class SenseOrPillPairingManager implements PairingManager {

    private final static Logger LOGGER = LoggerFactory.getLogger(SenseOrPillPairingManager.class);
    
    private final DeviceDAO deviceDAO;
    private final AlertsDAO alertsDAO;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final RegistrationLogger onboardingLogger;
    private final PillPairStateEvaluator pillPairStateEvaluator;
    private final SensePairStateEvaluator sensePairStateEvaluator;

    private SenseOrPillPairingManager(final DeviceDAO deviceDAO, AlertsDAO alertsDAO, final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                      final RegistrationLogger registrationLogger,
                                      final PillPairStateEvaluator pillPairStateEvaluator, final SensePairStateEvaluator sensePairStateEvaluator) {
        this.deviceDAO = deviceDAO;
        this.alertsDAO = alertsDAO;
        this.onboardingLogger = registrationLogger;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.pillPairStateEvaluator = pillPairStateEvaluator;
        this.sensePairStateEvaluator = sensePairStateEvaluator;
    }

    public static PairingManager create(final DeviceDAO deviceDAO, final AlertsDAO alertsDAO, final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                                   final PillPairStateEvaluator pillPairStateEvaluator, final SensePairStateEvaluator sensePairStateEvaluator) {
        return new SenseOrPillPairingManager(deviceDAO, alertsDAO, mergedUserInfoDynamoDB, new NoOpRegistrationLogger(), pillPairStateEvaluator, sensePairStateEvaluator);
    }

    public PairingManager withLogger(RegistrationLogger logger) {
        return new SenseOrPillPairingManager(deviceDAO, alertsDAO, mergedUserInfoDynamoDB, logger, pillPairStateEvaluator, sensePairStateEvaluator);
    }

    final void setPillColor(final String senseId, final long accountId, final String pillId){

        try {
            // WARNING: potential race condition here.
            final Optional<Color> pillColor = this.mergedUserInfoDynamoDB.setNextPillColor(senseId, accountId, pillId);
            if(pillColor.isPresent()) {
                LOGGER.info("Pill {} set to color {} on sense {}", pillId, pillColor.get(), senseId);
            } else {
                LOGGER.warn("Could not get next pill_color for pill {} on sense {}", pillId, senseId);
            }
        }catch (AmazonServiceException ase){
            LOGGER.error("Set pill {} color for sense {} failed: {}", pillId, senseId, ase.getErrorMessage());
        }
    }

    @Override
    public PairingResult pairSense(final PairingAttempt attempt) {
        final SenseCommandProtos.MorpheusCommand.Builder builder = attempt.builder;
        final boolean swapModeOn = true;
        final SensePairingRequest sensePairingRequest = SensePairingRequest.create(attempt.accountId, attempt.senseId, swapModeOn);
        final PairState pairState = sensePairStateEvaluator.getSensePairingStateAndMaybeSwap(sensePairingRequest);
        if (pairState == PairState.NOT_PAIRED) {
            this.deviceDAO.registerSense(attempt.accountId, attempt.senseId);
            onboardingLogger.logSuccess(Optional.<String>absent(),
                    String.format("Account id %d linked to senseId %s in DB.", attempt.accountId, attempt.senseId));
        }

        if(pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT){
            onboardingLogger.logProgress(Optional.<String>absent(),
                    String.format("Account id %d already linked to senseId %s in DB.", attempt.accountId, attempt.senseId));
        }

        if (pairState == PairState.NOT_PAIRED || pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT) {
            builder.setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE);
        } else {
            final String errorMessage = String.format("Account %d tries to pair multiple senses", attempt.accountId);
            LOGGER.error("error=pair-multiple-sense sense_id={} account_id={} ip_address={}", attempt.senseId, attempt.accountId, attempt.ipAddress);
            onboardingLogger.logFailure(Optional.absent(), errorMessage);

            builder.setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
            builder.setError(SenseCommandProtos.ErrorType.DEVICE_ALREADY_PAIRED);
        }

        return new PairingResult(builder, onboardingLogger);

    }

    @Override
    public PairingResult pairPill(final PairingAttempt attempt) {
        if(!attempt.pillId.isPresent()) {
            LOGGER.error("action=pair-pill error=missing-pill-id sense_id={} account_id={}", attempt.senseId, attempt.accountId);
            throw new RuntimeException("missing pill id in pill pairing attempt");
        }

        final String pillId = attempt.pillId.get();
        LOGGER.warn("action=pair-pill pill_id={} account_id={}", attempt.pillId, attempt.accountId);
        final PillPairingRequest pillPairingRequest = PillPairingRequest.from(attempt);
        final PairState pairState = pillPairStateEvaluator.getPillPairingState(pillPairingRequest, onboardingLogger);

        final SenseCommandProtos.MorpheusCommand.Builder builder = attempt.builder;
        
        builder.setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_PILL);
        switch (pairState) {
            case NOT_PAIRED:
                this.deviceDAO.registerPill(attempt.accountId, pillId);
                final String message = String.format("Linked pill %s to account %d in DB", attempt.pillId, attempt.accountId);
                LOGGER.warn(message);
                onboardingLogger.logSuccess(Optional.fromNullable(pillId), message);

                this.setPillColor(attempt.senseId, attempt.accountId, pillId);
                break;
            case PAIRED_WITH_OTHER_ACCOUNT:
                final List<DeviceAccountPair> pairedAccounts = this.deviceDAO.getLinkedAccountFromPillId(pillId);
                if(pairedAccounts.size() == 1) {
                    final DeviceAccountPair pair = pairedAccounts.get(0);

                    final int rowsUpdated = deviceDAO.updateAccountPairedForPill(attempt.accountId, pillId);
                    if(rowsUpdated == 1) {
                        this.setPillColor(attempt.senseId, attempt.accountId, pillId);
                        if(attempt.notifyOnConflict) {
                            // Best effort to insert pairing conflict alert
                            final Alert alert = Alert.pairingConflict(attempt.accountId, DateTime.now(DateTimeZone.UTC));
                            alertsDAO.insert(alert);
                        }
                    }
                }
                break;
            case PAIRED_WITH_CURRENT_ACCOUNT:
                onboardingLogger.logProgress(Optional.fromNullable(pillId),
                        String.format("Account id %d already linked to pill %s in DB.", attempt.accountId, pillId));
                break;

            case PAIRING_VIOLATION:
                // Override for pairing violation
                builder.setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                builder.setError(SenseCommandProtos.ErrorType.DEVICE_ALREADY_PAIRED);
                LOGGER.error("error=pill-already-paired pill_id={} account_id={} ip_address={}", pillId, attempt.accountId, attempt.ipAddress);
        }

        return new PairingResult(builder, onboardingLogger);
    }

    @Override
    public RegistrationLogger logger() {
        return onboardingLogger;
    }
}
