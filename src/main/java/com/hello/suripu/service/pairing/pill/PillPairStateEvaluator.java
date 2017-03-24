package com.hello.suripu.service.pairing.pill;

import com.google.common.base.Optional;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.service.pairing.PairState;
import com.hello.suripu.service.utils.RegistrationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PillPairStateEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PillPairStateEvaluator.class);

    private final DeviceDAO deviceDAO;

    public PillPairStateEvaluator(final DeviceDAO deviceDAO) {
        this.deviceDAO = deviceDAO;
    }

    public static PairState get(final PillPairingRequest request, List<DeviceAccountPair> pillsPairedToCurrentAccount, final List<DeviceAccountPair> accountsPairedToCurrentPill, final RegistrationLogger onboardingLogger) {
        if(pillsPairedToCurrentAccount.size() > 1){  // This account already paired with multiple pills
            LOGGER.warn("Account {} has already paired with multiple pills. pills paired {}, accounts paired {}",
                    request.accountId(),
                    pillsPairedToCurrentAccount.size(),
                    accountsPairedToCurrentPill.size());
            return PairState.PAIRING_VIOLATION;
        }

        if(accountsPairedToCurrentPill.isEmpty() && pillsPairedToCurrentAccount.isEmpty()){
            return PairState.NOT_PAIRED;
        }

        if(accountsPairedToCurrentPill.size() == 1 && pillsPairedToCurrentAccount.size() == 1 && pillsPairedToCurrentAccount.get(0).externalDeviceId.equals(request.pillId())){
            // might be a firmware retry
            return PairState.PAIRED_WITH_CURRENT_ACCOUNT;
        }


        if(request.debugMode()) {
            LOGGER.info("Debug mode for pairing pill {} to sense {}.", request.pillId(), request.senseId());
            if(pillsPairedToCurrentAccount.isEmpty()) {
                return PairState.NOT_PAIRED;
            }

            for(final DeviceAccountPair pill:pillsPairedToCurrentAccount){
                if(pill.externalDeviceId.equals(request.pillId())){
                    return PairState.PAIRED_WITH_CURRENT_ACCOUNT;
                }
            }
            final String errorMessage = String.format("Account %d already paired with %d pills.", request.accountId(), pillsPairedToCurrentAccount.size());
            LOGGER.error(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(request.pillId()), errorMessage);
            return PairState.PAIRING_VIOLATION;
        }

        if(accountsPairedToCurrentPill.size() > 1){
            final String errorMessage = String.format("Account %d already paired with multiple pills. pills paired %d, accounts paired %d",
                    request.accountId(),
                    pillsPairedToCurrentAccount.size(),
                    accountsPairedToCurrentPill.size());
            LOGGER.warn(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(request.pillId()), errorMessage);
            return PairState.PAIRING_VIOLATION;
        }

        // else:
        if(accountsPairedToCurrentPill.size() == 1 && pillsPairedToCurrentAccount.isEmpty()){
            // pill already paired with an account, but this account is new, stolen pill?
            final String errorMessage  = String.format("Pill %s might got stolen, account %d is a theft!",request.pillId(), request.accountId());
            LOGGER.error(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(request.pillId()), errorMessage);
            return PairState.PAIRED_WITH_OTHER_ACCOUNT;
        }
        if(pillsPairedToCurrentAccount.size() == 1 && accountsPairedToCurrentPill.isEmpty()){
            // account already paired with a pill, only one pill is allowed
            final String errorMessage = String.format("Account %d already paired with pill %s. Pill %s cannot pair to this account",
                    request.accountId(),
                    pillsPairedToCurrentAccount.get(0).externalDeviceId,
                    request.pillId());
            LOGGER.error(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(request.pillId()), errorMessage);

        }

        final String errorMessage = String.format("Paired failed for account %d. pills paired %d, accounts paired %d",
                request.accountId(),
                pillsPairedToCurrentAccount.size(),
                accountsPairedToCurrentPill.size());
        LOGGER.warn(errorMessage);
        onboardingLogger.logFailure(Optional.fromNullable(request.pillId()), errorMessage);

        return PairState.PAIRING_VIOLATION;
    }

    public PairState getPillPairingState(final PillPairingRequest request, final RegistrationLogger onboardingLogger) {
        final List<DeviceAccountPair> pillsPairedToCurrentAccount = this.deviceDAO.getPillsForAccountId(request.accountId());
        final List<DeviceAccountPair> accountsPairedToCurrentPill = this.deviceDAO.getLinkedAccountFromPillId(request.pillId());

        return PillPairStateEvaluator.get(request, pillsPairedToCurrentAccount, accountsPairedToCurrentPill, onboardingLogger);
    }
}
