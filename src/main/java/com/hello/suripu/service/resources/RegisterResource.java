package com.hello.suripu.service.resources;

import com.amazonaws.AmazonServiceException;
import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Optional;
import com.hello.dropwizard.mikkusu.helpers.AdditionalMediaTypes;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.api.ble.SenseCommandProtos.MorpheusCommand;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.oauth.ClientCredentials;
import com.hello.suripu.core.oauth.ClientDetails;
import com.hello.suripu.core.oauth.MissingRequiredScopeException;
import com.hello.suripu.core.oauth.OAuthScope;
import com.hello.suripu.core.oauth.stores.OAuthTokenStore;
import com.hello.suripu.core.swap.Swapper;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.core.util.PairAction;
import com.hello.suripu.coredw8.oauth.AccessToken;
import com.hello.suripu.coredw8.resources.BaseResource;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.pairing.PairState;
import com.hello.suripu.service.pairing.PillPairStateEvaluator;
import com.hello.suripu.service.pairing.PillPairingRequest;
import com.hello.suripu.service.pairing.SensePairStateEvaluator;
import com.hello.suripu.service.pairing.SensePairingRequest;
import com.hello.suripu.service.utils.RegistrationLogger;
import com.librato.rollout.RolloutClient;
import org.apache.commons.codec.binary.Hex;
import org.joda.time.DateTime;
import org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by pangwu on 10/10/14.
 */
@Path("/register")
public class RegisterResource extends BaseResource {
    private static final Pattern PG_UNIQ_PATTERN = Pattern.compile("ERROR: duplicate key value violates unique constraint \"(\\w+)\"");
    private static int PROTOBUF_VERSION = 0;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterResource.class);
    private final DeviceDAO deviceDAO;
    final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore;
    private final KinesisLoggerFactory kinesisLoggerFactory;
    private final KeyStore senseKeyStore;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final Swapper swapper;
    private final static String UNKNOWN_SENSE_ID = "UNKNOWN";
    private final PillPairStateEvaluator pillPairStateEvaluator;
    private final SensePairStateEvaluator sensePairStateEvaluator;

    @Context
    HttpServletRequest request;

    @Inject
    RolloutClient featureFlipper;

    private final GroupFlipper groupFlipper;

    public RegisterResource(final DeviceDAO deviceDAO,
                            final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore,
                            final KinesisLoggerFactory kinesisLoggerFactory,
                            final KeyStore senseKeyStore,
                            final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                            final Swapper swapper,
                            final GroupFlipper groupFlipper,
                            final PillPairStateEvaluator pillPairStateEvaluator,
                            final SensePairStateEvaluator sensePairStateEvaluator){

        this.deviceDAO = deviceDAO;
        this.tokenStore = tokenStore;
        this.swapper = swapper;
        this.kinesisLoggerFactory = kinesisLoggerFactory;
        this.senseKeyStore = senseKeyStore;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.groupFlipper = groupFlipper;
        this.pillPairStateEvaluator = pillPairStateEvaluator;
        this.sensePairStateEvaluator = sensePairStateEvaluator;
    }

    protected final boolean checkCommandType(final MorpheusCommand morpheusCommand, final PairAction action){
        switch (action){
            case PAIR_PILL:
                return morpheusCommand.getType() == MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_PILL;
            case PAIR_MORPHEUS:
                return morpheusCommand.getType() == MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE;
            default:
                return false;
        }
    }

    private boolean isPillPairingDebugMode(final String senseId) {
        final List<String> groups = groupFlipper.getGroups(senseId);
        return featureFlipper.deviceFeatureActive(FeatureFlipper.DEBUG_MODE_PILL_PAIRING, senseId, groups);
    }

    protected final void setPillColor(final String senseId, final long accountId, final String pillId){

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

    private final Optional<AccessToken> getClientDetailsByToken(final ClientCredentials credentials, final DateTime now) {
        try {
            return this.tokenStore.getTokenByClientCredentials(credentials, now);
        } catch (MissingRequiredScopeException e) {
            return Optional.absent();
        }
    }

    protected final MorpheusCommand.Builder pair(final String senseIdFromHeader, final byte[] encryptedRequest, final KeyStore keyStore, final PairAction action, final String ipAddress) {
        final MorpheusCommand.Builder builder = MorpheusCommand.newBuilder()
                .setVersion(PROTOBUF_VERSION);
        final DataLogger registrationLogger = kinesisLoggerFactory.get(QueueName.LOGS);
        final RegistrationLogger onboardingLogger = RegistrationLogger.create(senseIdFromHeader,
                action,
                ipAddress,
                registrationLogger);

        MorpheusCommand morpheusCommand = MorpheusCommand.getDefaultInstance();
        SignedMessage signedMessage = null;

        try {
            signedMessage = SignedMessage.parse(encryptedRequest);  // This call will throw
            morpheusCommand = MorpheusCommand.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            onboardingLogger.logFailure(Optional.<String>absent(), errorMessage);

            // We can't return a proper error because we can't decode the protobuf
            throwPlainTextError(Response.Status.BAD_REQUEST, "");
        } catch (RuntimeException rtEx){
            final String errorMessage = String.format("Failed parsing input: %s", rtEx.getMessage());
            LOGGER.error(errorMessage);

            onboardingLogger.logFailure(Optional.<String>absent(), errorMessage);

            // We can't return a proper error because we can't decode the protobuf
            throwPlainTextError(Response.Status.BAD_REQUEST, "");
        }

        final String deviceId = morpheusCommand.getDeviceId();
        builder.setDeviceId(deviceId);

        String senseId = "";
        String pillId = "";

        final String token = morpheusCommand.getAccountId();

        LOGGER.debug("deviceId = {}", deviceId);
        LOGGER.debug("token = {}", token);

        final Optional<AccessToken> accessTokenOptional = getClientDetailsByToken(
                new ClientCredentials(new OAuthScope[]{OAuthScope.AUTH}, token),
                DateTime.now());

        if(!accessTokenOptional.isPresent()) {
            builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
            builder.setError(SenseCommandProtos.ErrorType.INTERNAL_OPERATION_FAILED);
            final String logMessage = String.format("Token not found %s for device Id %s", token, deviceId);
            LOGGER.error(logMessage);
            onboardingLogger.logFailure(Optional.<String>absent(), logMessage);
            onboardingLogger.commit();
            return builder;
        }

        final Long accountId = accessTokenOptional.get().accountId;
        onboardingLogger.setAccountId(accountId);

        final String logMessage = String.format("AccountId from protobuf = %d", accountId);
        LOGGER.debug(logMessage);

        onboardingLogger.logProgress(Optional.<String>absent(), logMessage);

        // this is only needed for devices with 000... in the header
        // MUST BE CLEARED when the buffer is returned to Sense
        builder.setAccountId(token);

        switch (action) {
            case PAIR_MORPHEUS:
                senseId = deviceId;
                onboardingLogger.setSenseId(senseId);  // We need this until the provision problem got fixed.
                break;
            case PAIR_PILL:
                pillId = deviceId;
                final List<DeviceAccountPair> deviceAccountPairs = this.deviceDAO.getSensesForAccountId(accountId);
                if(deviceAccountPairs.isEmpty()){
                    final String errorMessage = String.format("No sense paired with account %d when pill %s tries to register",
                            accountId, pillId);
                    LOGGER.error(errorMessage);
                    onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);

                    builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                    builder.setError(SenseCommandProtos.ErrorType.INTERNAL_DATA_ERROR);

                    onboardingLogger.commit();
                    return builder;
                }
                senseId = deviceAccountPairs.get(0).externalDeviceId;
                break;
        }

        final Optional<byte[]> keyBytesOptional = keyStore.get(senseId);
        if(!keyBytesOptional.isPresent()) {
            final String errorMessage = String.format("Missing AES key for device = %s", senseId);
            LOGGER.error(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);

            throwPlainTextError(Response.Status.UNAUTHORIZED, "no key");
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(keyBytesOptional.get());

        if(error.isPresent()) {
            final String errorMessage = String.format("Fail to validate signature %s", error.get().message);
            LOGGER.error(errorMessage);
            onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);

            throwPlainTextError(Response.Status.UNAUTHORIZED, "invalid signature");
        }

        if(!checkCommandType(morpheusCommand, action)){
            builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
            builder.setError(SenseCommandProtos.ErrorType.INTERNAL_DATA_ERROR);

            final String errorMessage = String.format("Wrong request command type %s", morpheusCommand.getType().toString());
            LOGGER.error(errorMessage);
            LOGGER.error("error=wrong-request-command-type sense_id={}", senseId);
            onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);
            onboardingLogger.commit();

            return builder;
        }

        try {
            switch (action){
                case PAIR_MORPHEUS: {
                    final PairState pairState = sensePairStateEvaluator.getSensePairingState(SensePairingRequest.create(accountId, senseId));
                    if (pairState == PairState.NOT_PAIRED) {
                        this.deviceDAO.registerSense(accountId, senseId);
                        onboardingLogger.logSuccess(Optional.<String>absent(),
                                String.format("Account id %d linked to senseId %s in DB.", accountId, senseId));
                    }

                    if(pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT){
                        onboardingLogger.logProgress(Optional.<String>absent(),
                                String.format("Account id %d already linked to senseId %s in DB.", accountId, senseId));
                    }

                    if (pairState == PairState.NOT_PAIRED || pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT) {
                        builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE);
                    } else {
                        final String errorMessage = String.format("Account %d tries to pair multiple senses", accountId);
                        LOGGER.error("error=pair-multiple-sense sense_id={} account_id={} ip_address={}", senseId, accountId, ipAddress);
                        onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);

                        builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                        builder.setError(SenseCommandProtos.ErrorType.DEVICE_ALREADY_PAIRED);
                    }
                }
                break;
                case PAIR_PILL: {
                    LOGGER.warn("action=pair-pill pill_id={} account_id={}", pillId, accountId);
                    final PillPairingRequest pillPairingRequest = PillPairingRequest.create(senseId, pillId, accountId, isPillPairingDebugMode(senseId));
                    final PairState pairState = pillPairStateEvaluator.getPillPairingState(pillPairingRequest, onboardingLogger);
                    if (pairState == PairState.NOT_PAIRED) {
                        this.deviceDAO.registerPill(accountId, deviceId);
                        final String message = String.format("Linked pill %s to account %d in DB", pillId, accountId);
                        LOGGER.warn(message);
                        onboardingLogger.logSuccess(Optional.fromNullable(pillId), message);

                        this.setPillColor(senseId, accountId, deviceId);
                    }

                    if(pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT){
                        onboardingLogger.logProgress(Optional.fromNullable(pillId),
                                String.format("Account id %d already linked to pill %s in DB.", accountId, pillId));
                    }

                    if (pairState == PairState.NOT_PAIRED || pairState == PairState.PAIRED_WITH_CURRENT_ACCOUNT) {
                        builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_PILL);
                    } else {
                        builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                        builder.setError(SenseCommandProtos.ErrorType.DEVICE_ALREADY_PAIRED);
                        LOGGER.error("error=pill-already-paired pill_id={} account_id={} ip_address={}", pillId, accountId, ipAddress);
                    }
                }
                break;
            }
            //builder.setAccountId(morpheusCommand.getAccountId());

        } catch (UnableToExecuteStatementException sqlExp){
            final Matcher matcher = PG_UNIQ_PATTERN.matcher(sqlExp.getMessage());
            if (!matcher.find()) {
                final String errorMessage = String.format("SQL error %s", sqlExp.getMessage());
                LOGGER.error(errorMessage);

                onboardingLogger.logFailure(Optional.fromNullable(pillId), errorMessage);

                builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                builder.setError(SenseCommandProtos.ErrorType.INTERNAL_OPERATION_FAILED);
            }else {
                LOGGER.error(sqlExp.getMessage());
                onboardingLogger.logFailure(Optional.fromNullable(pillId), sqlExp.getMessage());

                //TODO: enforce the constraint
                LOGGER.warn("Account {} tries to pair a paired device {} ",
                        accountId, deviceId);
                builder.setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR);
                builder.setError(SenseCommandProtos.ErrorType.DEVICE_ALREADY_PAIRED);
            }
        }

        try {
            final String ip = request.getHeader("X-Forwarded-For");
            final DataLogger dataLogger = kinesisLoggerFactory.get(QueueName.REGISTRATIONS);
            final LoggingProtos.Registration.Builder registration = LoggingProtos.Registration.newBuilder().setAccountId(accountId)
                    .setDeviceId(deviceId)
                    .setTimestamp(DateTime.now().getMillis());

            if (ip != null) {
                registration.setIpAddress(ip);
            }


            dataLogger.put(accountId.toString(), registration.build().toByteArray());
        } catch (Exception e) {
            LOGGER.error("error=failed-registration-insert-kinesis message={}", e.getMessage());
        }

        onboardingLogger.commit();
        return builder;
    }

    private byte[] signAndSend(final String senseId, final MorpheusCommand.Builder morpheusCommandBuilder, final KeyStore keyStore) {
        final Optional<byte[]> keyBytesOptional = keyStore.get(senseId);
        if(!keyBytesOptional.isPresent()) {
            LOGGER.error("error=missing-aes-key sense_id={}", senseId);
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }
        LOGGER.trace("Key used to sign device {} : {}", senseId, Hex.encodeHexString(keyBytesOptional.get()));

        final Optional<byte[]> signedResponse = SignedMessage.sign(morpheusCommandBuilder.build().toByteArray(), keyBytesOptional.get());
        if(!signedResponse.isPresent()) {
            LOGGER.error("Failed signing message for deviceId = {}", senseId);
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }

        return signedResponse.get();
    }

    @POST
    @Path("/morpheus")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Deprecated
    @Timed
    public byte[] registerMorpheus(final byte[] body) {

        final String senseIdFromHeader = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if(senseIdFromHeader != null){
            LOGGER.info("Sense Id from http header {}", senseIdFromHeader);
        }
        final MorpheusCommand.Builder builder = pair(senseIdFromHeader, body, senseKeyStore, PairAction.PAIR_MORPHEUS, getIpAddress(request));
        builder.clearAccountId();
        if(senseIdFromHeader != null && senseIdFromHeader.equals(KeyStoreDynamoDB.DEFAULT_FACTORY_DEVICE_ID)){
            senseKeyStore.put(builder.getDeviceId(), Hex.encodeHexString(KeyStoreDynamoDB.DEFAULT_AES_KEY));
            LOGGER.error("Key for device {} has been automatically generated", builder.getDeviceId());
        }

        return signAndSend(builder.getDeviceId(), builder, senseKeyStore);
    }

    @POST
    @Path("/sense")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] registerSense(final byte[] body) {
        final String senseIdFromHeader = this.request.getHeader(HelloHttpHeader.SENSE_ID);

        final String senseId = (senseIdFromHeader != null) ? senseIdFromHeader : UNKNOWN_SENSE_ID;
        final String middleFW = getFirmwareVersion(request, HelloHttpHeader.MIDDLE_FW_VERSION);
        final String topFW = getFirmwareVersion(request, HelloHttpHeader.TOP_FW_VERSION);

        final String ipAddress = getIpAddress(request);
        LOGGER.info("action=pair-sense sense_id={} ip_address={} fw_version={}, top_fw_version={}", senseId, ipAddress, middleFW, topFW);

        final MorpheusCommand.Builder builder = pair(senseIdFromHeader, body, senseKeyStore, PairAction.PAIR_MORPHEUS, ipAddress);
        if(senseIdFromHeader != null){
            return signAndSend(senseIdFromHeader, builder, senseKeyStore);
        }
        return signAndSend(builder.getDeviceId(), builder, senseKeyStore);
    }

    @POST
    @Path("/pill")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] registerPill(final byte[] body) {
        final String senseIdFromHeader = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        final String senseId = (senseIdFromHeader != null) ? senseIdFromHeader : UNKNOWN_SENSE_ID;

        final String middleFW = getFirmwareVersion(request, HelloHttpHeader.MIDDLE_FW_VERSION);
        final String topFW = getFirmwareVersion(request, HelloHttpHeader.TOP_FW_VERSION);
        final String ipAddress = getIpAddress(request);
        LOGGER.info("action=pair-pill sense_id={} ip_address={} fw_version={}, top_fw_version={}", senseId, ipAddress, middleFW, topFW);

        final MorpheusCommand.Builder builder = pair(senseIdFromHeader, body, senseKeyStore, PairAction.PAIR_PILL, ipAddress);
        final String token = builder.getAccountId();


        // WARNING: never return the account id, it will overflow buffer for old versions
        builder.clearAccountId();


        if(senseIdFromHeader != null && !senseIdFromHeader.equals(KeyStoreDynamoDB.DEFAULT_FACTORY_DEVICE_ID)){
            LOGGER.info("Sense Id from http header {}", senseIdFromHeader);
            return signAndSend(senseIdFromHeader, builder, senseKeyStore);
        }

        LOGGER.warn("action=pair-pill-token-lookup sense_id={} ip_address={} fw_version={}, top_fw_version={}", senseId, ipAddress, middleFW, topFW);
        // TODO: Remove this and get sense id from header after the firmware is fixed.
        final Optional<AccessToken> accessTokenOptional = getClientDetailsByToken(
                new ClientCredentials(new OAuthScope[]{OAuthScope.AUTH}, token),
                DateTime.now());

        if(!accessTokenOptional.isPresent()) {
            LOGGER.error("Did not find accessToken {}", token);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final Long accountId = accessTokenOptional.get().accountId;
        final List<DeviceAccountPair> deviceAccountPairs = this.deviceDAO.getSensesForAccountId(accountId);
        if(deviceAccountPairs.isEmpty()) {
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final String senseIdFromDB = deviceAccountPairs.get(0).externalDeviceId;
        return signAndSend(senseIdFromDB, builder, senseKeyStore);
    }

    // TODO: move this in base resource
    private static String getFirmwareVersion(final HttpServletRequest request, final String board) {
        final String fwVersion =
                (request.getHeader(board) != null)
                ? request.getHeader(board)
                : "0";
        return fwVersion;
    }
}
