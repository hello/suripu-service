package com.hello.suripu.service.resources;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.TextFormat;
import com.hello.dropwizard.mikkusu.helpers.AdditionalMediaTypes;
import com.hello.suripu.api.audio.AudioControlProtos;
import com.hello.suripu.api.audio.AudioFeaturesControlProtos;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.api.expansions.ExpansionProtos;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.api.input.State;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.ResponseCommandsDAODynamoDB;
import com.hello.suripu.core.db.ResponseCommandsDAODynamoDB.ResponseCommand;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SenseStateDynamoDB;
import com.hello.suripu.core.firmware.FirmwareUpdate;
import com.hello.suripu.core.firmware.FirmwareUpdateStore;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.firmware.SenseFirmwareUpdateQuery;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.hello.suripu.core.models.Alarm;
import com.hello.suripu.core.models.AlarmExpansion;
import com.hello.suripu.core.models.Calibration;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.SenseStateAtTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.processors.OTAProcessor;
import com.hello.suripu.core.processors.RingProcessor;
import com.hello.suripu.core.roomstate.Condition;
import com.hello.suripu.core.roomstate.CurrentRoomState;
import com.hello.suripu.core.util.DateTimeUtil;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.core.util.RoomConditionUtil;
import com.hello.suripu.core.util.SenseLogLevelUtil;
import com.hello.suripu.coredropwizard.resources.BaseResource;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.Util;
import com.hello.suripu.service.configuration.OTAConfiguration;
import com.hello.suripu.service.configuration.SenseUploadConfiguration;
import com.hello.suripu.service.file_sync.FileManifestUtil;
import com.hello.suripu.service.file_sync.FileSynchronizer;
import com.hello.suripu.service.models.UploadSettings;
import com.hello.suripu.service.utils.ServiceFeatureFlipper;
import com.librato.rollout.RolloutClient;
import org.apache.commons.codec.binary.Hex;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;


@Path("/in")
public class ReceiveResource extends BaseResource {

    @Inject
    RolloutClient featureFlipper;


    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiveResource.class);
    private static final int CLOCK_SKEW_TOLERATED_IN_HOURS = 2;
    private static final int CLOCK_DRIFT_MEASUREMENT_THRESHOLD = 2;
    private static final int CLOCK_BUG_SKEW_IN_HOURS = 6 * 30 * 24 - 1; // 6 months in hours
    private static final String LOCAL_OFFICE_IP_ADDRESS = "204.28.123.251";
    private static final String FW_VERSION_0_9_22_RC7 = "1530439804";
    private static final Integer CLOCK_SYNC_SPECIAL_OTA_UPTIME_MINS = 15;
    private static final Integer ALARM_ACTIONS_WINDOW_MINS = 60;
    private static final Integer RING_UPTIME_THRESHOLD = 30; //mins
    private final int ringDurationSec;

    private final KeyStore keyStore;
    private final MergedUserInfoDynamoDB mergedInfoDynamoDB;
    private final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB;
    private final SenseStateDynamoDB senseStateDynamoDB;

    // File endpoint
    private final FileSynchronizer fileSynchronizer;

    private final KinesisLoggerFactory kinesisLoggerFactory;
    private final Boolean debug;

    private final FirmwareUpdateStore firmwareUpdateStore;
    private final GroupFlipper groupFlipper;
    private final SenseUploadConfiguration senseUploadConfiguration;
    private final OTAConfiguration otaConfiguration;
    private final ResponseCommandsDAODynamoDB responseCommandsDAODynamoDB;

    private final MetricRegistry metrics;
    protected Meter senseClockOutOfSync;
    protected Meter senseClockOutOfSync3h;
    protected Meter pillClockOutOfSync;
    protected Meter filesMarkedForDownload;
    protected final Meter sdCardFailures;
    protected final Histogram sdCardFreeMemoryKiloBytes;
    protected Meter otaFileResponses;
    protected Histogram drift;
    private final CalibrationDAO calibrationDAO;

    @Context
    HttpServletRequest request;

    public ReceiveResource(final KeyStore keyStore,
                           final KinesisLoggerFactory kinesisLoggerFactory,
                           final MergedUserInfoDynamoDB mergedInfoDynamoDB,
                           final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB,
                           final Boolean debug,
                           final FirmwareUpdateStore firmwareUpdateStore,
                           final GroupFlipper groupFlipper,
                           final SenseUploadConfiguration senseUploadConfiguration,
                           final OTAConfiguration otaConfiguration,
                           final ResponseCommandsDAODynamoDB responseCommandsDAODynamoDB,
                           final int ringDurationSec,
                           final CalibrationDAO calibrationDAO,
                           final MetricRegistry metricRegistry,
                           final SenseStateDynamoDB senseStateDynamoDB,
                           final FileSynchronizer fileSynchronizer) {

        this.keyStore = keyStore;
        this.kinesisLoggerFactory = kinesisLoggerFactory;

        this.mergedInfoDynamoDB = mergedInfoDynamoDB;
        this.ringTimeHistoryDAODynamoDB = ringTimeHistoryDAODynamoDB;
        this.metrics= metricRegistry;

        this.debug = debug;

        this.firmwareUpdateStore = firmwareUpdateStore;
        this.groupFlipper = groupFlipper;
        this.senseUploadConfiguration = senseUploadConfiguration;
        this.otaConfiguration = otaConfiguration;
        this.responseCommandsDAODynamoDB = responseCommandsDAODynamoDB;
        this.senseClockOutOfSync = metrics.meter(name(ReceiveResource.class, "sense-clock-out-sync"));
        this.senseClockOutOfSync3h = metrics.meter(name(ReceiveResource.class, "sense-clock-out-sync-3h"));
        this.pillClockOutOfSync = metrics.meter(name(ReceiveResource.class, "pill-clock-out-sync"));
        this.drift = metrics.histogram(name(ReceiveResource.class, "sense-drift"));
        this.filesMarkedForDownload = metrics.meter(name(ReceiveResource.class, "files-marked-for-download"));
        this.sdCardFailures = metrics.meter(name(ReceiveResource.class, "sd-card-failures"));
        this.sdCardFreeMemoryKiloBytes = metrics.histogram(name(ReceiveResource.class, "sd-card-free-memory-kb"));
        this.otaFileResponses = metrics.meter(name(ReceiveResource.class, "ota-file-responses"));
        this.ringDurationSec = ringDurationSec;
        this.calibrationDAO = calibrationDAO;
        this.senseStateDynamoDB = senseStateDynamoDB;
        this.fileSynchronizer = fileSynchronizer;
    }


    @POST
    @Path("/sense/batch")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] receiveBatchSenseData(final byte[] body) {


        final SignedMessage signedMessage = SignedMessage.parse(body);
        DataInputProtos.batched_periodic_data data = null;

        String debugSenseId = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if (debugSenseId == null) {
            debugSenseId = "";
        }

        final String topFW = Util.getFWVersionFromHeader(this.request, HelloHttpHeader.TOP_FW_VERSION);
        final String middleFW = Util.getFWVersionFromHeader(this.request, HelloHttpHeader.MIDDLE_FW_VERSION);
        final HardwareVersion hardwareVersion = Util.getHardwareVersionFromHeader(this.request);

        LOGGER.debug("sense_id={}", debugSenseId);
        final String ipAddress = getIpAddress(request);

        try {
            data = DataInputProtos.batched_periodic_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            LOGGER.error("error=protobuf-parsing-failed sense_id={} ip_address={} message={}", debugSenseId, ipAddress, exception.getMessage());
            return plainTextError(Response.Status.BAD_REQUEST, "bad request");
        }
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));


        LOGGER.debug("Received valid protobuf {}", data.toString());
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));

        if (!data.hasDeviceId() || data.getDeviceId().isEmpty()) {
            LOGGER.error("error=empty-device-id-protobuf sense_id={}", debugSenseId);
            return plainTextError(Response.Status.BAD_REQUEST, "empty device id");
        }


        final String deviceId = data.getDeviceId();
        final List<String> groups = groupFlipper.getGroups(deviceId);

        if (featureFlipper.deviceFeatureActive(FeatureFlipper.PRINT_RAW_PB, deviceId, groups)) {
            LOGGER.debug("sense_id={} raw_pb={}", deviceId, Hex.encodeHexString(body));
        }

        final Optional<byte[]> optionalKeyBytes = getKey(deviceId, groups, ipAddress);

        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("error=key-store-failure sense_id={}", deviceId);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("error=signature-failed sense_id={} ip_address={} message={}", deviceId, ipAddress, error.get().message);
            return plainTextError(Response.Status.UNAUTHORIZED, "");
        }


        final List<UserInfo> userInfoList = new ArrayList<>();

        try {
            userInfoList.addAll(this.mergedInfoDynamoDB.getInfo(data.getDeviceId()));  // get alarm related info from DynamoDB "cache".
        } catch (Exception ex) {
            LOGGER.error("error=merge-info-retrieve-failure sense_id={} message={}", deviceId, ex.getMessage());
        }
        LOGGER.debug("accounts_paired={} sense_id={}", userInfoList.size(), data.getDeviceId());

        final Map<Long, DateTimeZone> accountTimezones = getUserTimeZones(userInfoList);
        final DataInputProtos.BatchPeriodicDataWorker.Builder batchPeriodicDataWorkerMessageBuilder = DataInputProtos.BatchPeriodicDataWorker.newBuilder()
                .setData(data)
                .setReceivedAt(DateTime.now().getMillis())
                .setIpAddress(ipAddress)
                .setFirmwareMiddleVersion(middleFW)
                .setFirmwareTopVersion(topFW)
                .setUptimeInSecond(data.getUptimeInSecond());


        for (final Long accountId : accountTimezones.keySet()) {
            final DataInputProtos.AccountMetadata metadata = DataInputProtos.AccountMetadata.newBuilder()
                    .setAccountId(accountId)
                    .setTimezone(accountTimezones.get(accountId).getID())
                    .build();
            batchPeriodicDataWorkerMessageBuilder.addTimezones(metadata);
        }

        try {
            final DataLogger batchSenseDataLogger = kinesisLoggerFactory.get(QueueName.SENSE_SENSORS_DATA);
            batchSenseDataLogger.put(data.getDeviceId(), batchPeriodicDataWorkerMessageBuilder.build().toByteArray());
        } catch (Exception e) {
            LOGGER.error("error=kinesis-insert-sense_sensors_data {}", e.getMessage());
            return plainTextError(Response.Status.SERVICE_UNAVAILABLE, "");
        }

        final String tempSenseId = data.hasDeviceId() ? data.getDeviceId() : debugSenseId;
        return generateSyncResponse(tempSenseId, data.getFirmwareVersion(), optionalKeyBytes.get(), data, userInfoList, ipAddress, hardwareVersion);
    }


    private Boolean isValidSenseState(final State.SenseState senseState) {
        if (senseState.hasAudioState()) {
            if (senseState.getAudioState().hasFilePath() && senseState.getAudioState().getFilePath().isEmpty()) {
                LOGGER.error("class=SenseState sense_id={} error=empty-audio-state-file-path", senseState.getSenseId());
                return false;
            }
        }
        return true;
    }

    @POST
    @Path("/sense/state")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] updateSenseState(final byte[] body) {
        final SignedMessage signedMessage = SignedMessage.parse(body);
        final State.SenseState senseState;

        String debugSenseId = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if (debugSenseId == null) {
            debugSenseId = "";
        }

        LOGGER.debug("sense_id={}", debugSenseId);

        try {
            senseState = State.SenseState.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            LOGGER.error("error=failed-parsing-protobuf sense_id={} exception={}",
                    debugSenseId, exception.getMessage());
            return plainTextError(Response.Status.BAD_REQUEST, "bad request");
        }

        LOGGER.info("endpoint=sense-state sense_id={} protobuf-message={}",
                debugSenseId, TextFormat.shortDebugString(senseState));

        if (!senseState.hasSenseId() || senseState.getSenseId().isEmpty()) {
            LOGGER.error("endpoint=sense-state error=empty-device-id sense_id={}", debugSenseId);
            return plainTextError(Response.Status.BAD_REQUEST, "empty device id");
        }


        final String senseId = senseState.getSenseId();
        final List<String> groups = groupFlipper.getGroups(senseId);
        final String ipAddress = getIpAddress(request);

        if (!senseId.equals(debugSenseId)) {
            LOGGER.error("endpoint=sense-state error=sense-id-no-match sense_id={} proto-sense-id={}", debugSenseId, senseId);
            return plainTextError(Response.Status.BAD_REQUEST, "Device ID doesn't match header");
        }

        final Optional<byte[]> optionalKeyBytes = getKey(senseId, groups, ipAddress);

        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("endpoint=sense-state error=key-store-failure sense_id={}", senseId);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("endpoint=sense-state error={} sense_id={}", error.get().message, senseId);
            return plainTextError(Response.Status.UNAUTHORIZED, "");
        }

        if (!isValidSenseState(senseState)) {
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        // Update state in Dynamo
        senseStateDynamoDB.updateState(new SenseStateAtTime(senseState, DateTime.now(DateTimeZone.UTC)));

        final Optional<byte[]> signedResponse = SignedMessage.sign(senseState.toByteArray(), optionalKeyBytes.get());
        if (!signedResponse.isPresent()) {
            LOGGER.error("endpoint=sense-state error=failed-signing-message sense_id={}", senseId);
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }

        return signedResponse.get();
    }


    @POST
    @Path("/sense/files")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] updateFileManifest(final byte[] body) {
        // TODO ALL OF this needs to be refactored.
        String debugSenseId = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if (debugSenseId == null) {
            debugSenseId = "";
        }

        LOGGER.debug("endpoint=files sense_id={}", debugSenseId);

        final SignedMessage signedMessage = SignedMessage.parse(body);
        final FileSync.FileManifest fileManifest;

        try {
            fileManifest = FileSync.FileManifest.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            LOGGER.error("error=failed-parsing-protobuf sense_id={} exception={}",
                    debugSenseId, exception.getMessage());
            return plainTextError(Response.Status.BAD_REQUEST, "bad request");
        }

        LOGGER.debug("endpoint=files sense_id={} protobuf-message={}",
                debugSenseId, TextFormat.shortDebugString(fileManifest));

        if (!fileManifest.hasSenseId() || fileManifest.getSenseId().isEmpty()) {
            LOGGER.error("endpoint=files error=manifest-empty-device-id");
            return plainTextError(Response.Status.BAD_REQUEST, "empty device id");
        }


        final String senseId = fileManifest.getSenseId();
        final List<String> groups = groupFlipper.getGroups(senseId);
        final String ipAddress = getIpAddress(request);

        if (!senseId.equals(debugSenseId)) {
            LOGGER.error("endpoint=files error=sense-id-no-match sense_id={} proto-sense-id={}", debugSenseId, senseId);
            return plainTextError(Response.Status.BAD_REQUEST, "Device ID doesn't match header");
        }

        final Optional<byte[]> optionalKeyBytes = getKey(senseId, groups, ipAddress);

        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("endpoint=files error=key-store-failure sense_id={}", senseId);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("endpoint=files signed-message-error={} sense_id={}", error.get().message, senseId);
            return plainTextError(Response.Status.UNAUTHORIZED, "");
        }
        // END refactoring TODO

        if (!fileManifest.hasFirmwareVersion()) {
            LOGGER.error("endpoint=files error=no-firmware-version sense_id={}", senseId);
            return plainTextError(Response.Status.BAD_REQUEST, "no firmware version");
        }

        // Mark SD card metrics
        if (fileManifest.hasSdCardSize()) {
            if (FileManifestUtil.hasFailedSdCard(fileManifest)) {
                sdCardFailures.mark();
            }
            if (fileManifest.getSdCardSize().hasFreeMemory()) {
                sdCardFreeMemoryKiloBytes.update(fileManifest.getSdCardSize().getFreeMemory());
            }
        }



        final HardwareVersion hardwareVersion = Util.getHardwareVersionFromHeader(this.request);

        // Synchronize
        Boolean fileDownloadsDisabled = featureFlipper.deviceFeatureActive(ServiceFeatureFlipper.FILE_DOWNLOAD_DISABLED.getFeatureName(), senseId, groups);

        // Sense 1p5 production should not download new files
        // what you have on sense is what you should have from server
        // this is a hack because I don't have time to do the hardware version filtering properly
        // For DVT sense they will rely on the sense_file_info table to force download non public files
        final Boolean isInDVTList = featureFlipper.deviceFeatureActive(ServiceFeatureFlipper.IS_SENSE_ONE_FIVE_DVT_UNIT.getFeatureName(), senseId, groups);
        if(HardwareVersion.SENSE_ONE_FIVE.equals(hardwareVersion)) {
            fileDownloadsDisabled = true;
            if(isInDVTList) {
                fileDownloadsDisabled = false; // override for DVT only
            }
        }


        final FileSync.FileManifest newManifest = fileSynchronizer.synchronizeFileManifest(senseId, fileManifest, !fileDownloadsDisabled);

        // Mark any updates we're sending
        for (final FileSync.FileManifest.File file : newManifest.getFileInfoList()) {
            // If marked for update and not delete, it's marked for download.
            if (file.hasUpdateFile() && file.getUpdateFile() && !(file.hasDeleteFile() && file.getDeleteFile())) {
                filesMarkedForDownload.mark();
            }
        }

        LOGGER.info("endpoint=files response-protobuf={}", TextFormat.shortDebugString(newManifest));

        // TODO this could most likely be refactored as well
        final Optional<byte[]> signedResponse = SignedMessage.sign(newManifest.toByteArray(), optionalKeyBytes.get());
        if (!signedResponse.isPresent()) {
            LOGGER.error("endpoint=files error=failed-signing-message sense-id={}", senseId);
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }

        return signedResponse.get();
    }


    public static OutputProtos.SyncResponse.Builder setPillColors(final List<UserInfo> userInfoList,
                                                                  final OutputProtos.SyncResponse.Builder syncResponseBuilder) {
        final ArrayList<OutputProtos.SyncResponse.PillSettings> pillSettings = new ArrayList<>();
        for (final UserInfo userInfo : userInfoList) {
            if (userInfo.pillColor.isPresent()) {
                pillSettings.add(userInfo.pillColor.get());
            }
        }

        for (int i = pillSettings.size() - 1; i >= 0 && i >= pillSettings.size() - 2; i--) {
            syncResponseBuilder.addPillSettings(pillSettings.get(i));
        }

        return syncResponseBuilder;
    }

    /**
     * Persists data and generates SyncResponse
     * @param deviceName
     * @param firmwareVersion
     * @param encryptionKey
     * @param batch
     * @return
     */
    private byte[] generateSyncResponse(final String deviceName,
                                        final int firmwareVersion,
                                        final byte[] encryptionKey,
                                        final DataInputProtos.batched_periodic_data batch,
                                        final List<UserInfo> userInfoList,
                                        final String ipAddress,
                                        final HardwareVersion hardwareVersion) {
        // TODO: Warning, since we query dynamoDB based on user input, the user can generate a lot of
        // requests to break our bank(Assume that Dynamo DB never goes down).
        // May be we should somehow cache these data to reduce load & cost.

        final OutputProtos.SyncResponse.Builder responseBuilder = OutputProtos.SyncResponse.newBuilder();

        final List<String> groups = groupFlipper.getGroups(deviceName);
        Boolean deviceHasOutOfSyncClock = false;
        final Integer numMessagesInQueue = (batch.hasMessagesInQueue()) ? batch.getMessagesInQueue() : 0;

        for (int i = 0; i < batch.getDataCount(); i++) {
            final DataInputProtos.periodic_data data = batch.getData(i);
            final Long timestampMillis = data.getUnixTime() * 1000L;
            final DateTime roundedDateTime = new DateTime(timestampMillis, DateTimeZone.UTC).withSecondOfMinute(0);

            if (featureFlipper.deviceFeatureActive(FeatureFlipper.MEASURE_CLOCK_DRIFT, deviceName, groups)) {
                final int driftInMinutes = Minutes.minutesBetween(DateTime.now(DateTimeZone.UTC), roundedDateTime).getMinutes();
                this.drift.update(Math.abs(driftInMinutes));
                if(Math.abs(driftInMinutes) >= CLOCK_DRIFT_MEASUREMENT_THRESHOLD) {
                    LOGGER.warn("action=measure-clock-drift drift={} sense_id={} number_samples={} fw_version={} ip_address={}",
                            driftInMinutes,
                            deviceName,
                            batch.getDataCount(),
                            batch.getFirmwareVersion(),
                            ipAddress
                    );
                }
            }

            if(isClockOutOfSync(roundedDateTime, DateTime.now(DateTimeZone.UTC), CLOCK_SKEW_TOLERATED_IN_HOURS)) {
                LOGGER.error("The clock for device {} is not within reasonable bounds (2h), current time = {}, received time = {}",
                        deviceName,
                        DateTime.now(),
                        roundedDateTime
                );

                LOGGER.error("error=clock-out-of-sync sense_id={} current_time={} received_time={} fw_version={} ip_address={} num_messages={}",
                        deviceName,
                        DateTime.now(),
                        roundedDateTime,
                        batch.getFirmwareVersion(),
                        ipAddress,
                        numMessagesInQueue);

                // TODO: throw exception?
                senseClockOutOfSync.mark(1);
                deviceHasOutOfSyncClock = true;

                // Additional logic to measure clock drift
                // Sense keeps up to 3h of data in case of connection issue. We'd like to measure how many devices are outside these bounds
                if(isClockOutOfSync(roundedDateTime, DateTime.now(DateTimeZone.UTC), CLOCK_SKEW_TOLERATED_IN_HOURS + 1)) {
                    senseClockOutOfSync3h.mark(1);
                }


                // TODO: pull firmware version dynamically
                final Set<Integer> fwVersionsToRebootIfClockOutOfSync = Sets.newHashSet(
                    1425228832,  // 1.0.5.2
                    510963780,   // 1.0.5.3.1
                    782503713,   // 1.0.5.3.4
                    2121778303,  // 1.0.5.3.5
                    3892         // 1.8.1
                );

                final boolean isLatestFirmware = batch.hasFirmwareVersion() && fwVersionsToRebootIfClockOutOfSync.contains(batch.getFirmwareVersion());
                if (featureFlipper.deviceFeatureActive(FeatureFlipper.REBOOT_CLOCK_OUT_OF_SYNC_DEVICES, deviceName, groups) && isLatestFirmware) {
                    LOGGER.warn("Reset MCU set for sense {}", deviceName); // keeping this for papertrail alerts
                    LOGGER.warn("action=reset-mcu sense_id={}", deviceName);
                    responseBuilder.setResetMcu(true);
                } else {
                    continue;
                }
            }

            // only compute the state for the most recent conditions

            if (i == batch.getDataCount() - 1) {
                final Optional<Calibration> calibrationOptional = this.hasCalibrationEnabled(deviceName) ? calibrationDAO.get(deviceName) : Optional.<Calibration>absent();
                if(calibrationOptional.isPresent()) {
                    responseBuilder.setLightsOffThreshold(calibrationOptional.get().lightsOutDelta());
                    LOGGER.trace("sense_id={} lights_out_delta={}", deviceName, calibrationOptional.get().lightsOutDelta());
                }
                final CurrentRoomState currentRoomState = CurrentRoomState.fromRawData(data.getTemperature(), data.getHumidity(), data.getDustMax(), data.getLight(), data.getAudioPeakBackgroundEnergyDb(), data.getAudioPeakDisturbanceEnergyDb(),
                        roundedDateTime.getMillis(),
                        data.getFirmwareVersion(),
                        DateTime.now(),
                        2,
                        calibrationOptional);

                if (featureFlipper.deviceFeatureActive(FeatureFlipper.NEW_ROOM_CONDITION, deviceName, groups)) {
                    final Boolean hasCalibration = featureFlipper.deviceFeatureActive(FeatureFlipper.CALIBRATION, deviceName, groups);
                    final Condition roomConditions = RoomConditionUtil.getGeneralRoomConditionV2(currentRoomState, hasCalibration && calibrationOptional.isPresent());
                    final Condition roomConditionsLightsOff = RoomConditionUtil.getRoomConditionV2LightOff(currentRoomState, hasCalibration && calibrationOptional.isPresent());
                    responseBuilder.setRoomConditions(
                            OutputProtos.SyncResponse.RoomConditions.valueOf(
                                    roomConditions.ordinal()));

                    responseBuilder.setRoomConditionsLightsOff(
                            OutputProtos.SyncResponse.RoomConditions.valueOf(
                                    roomConditionsLightsOff.ordinal()));
                } else {
                    responseBuilder.setRoomConditions(
                            OutputProtos.SyncResponse.RoomConditions.valueOf(
                                    RoomConditionUtil.getGeneralRoomCondition(currentRoomState).ordinal()));
                }

            }
        }

        final Optional<DateTimeZone> userTimeZone = getUserTimeZone(userInfoList);

        final int uptime;
        if (batch.hasUptimeInSecond()){
            uptime= batch.getUptimeInSecond();
        } else {
            uptime= 0;
        }

        final boolean hasSufficientUptime;
        if (uptime < DateTimeConstants.SECONDS_PER_MINUTE * RING_UPTIME_THRESHOLD){ //smart alarm window = 30 minutes.
            hasSufficientUptime = false;
        } else {
            hasSufficientUptime = true;
        }

        if (userTimeZone.isPresent()) {
            final RingTime nextRingTime = RingProcessor.getNextRingTimeForSense(deviceName, userInfoList, DateTime.now(), hasSufficientUptime);

            // WARNING: now must generated after getNextRingTimeForSense, because that function can take a long time.
            final DateTime now = Alarm.Utils.alignToMinuteGranularity(DateTime.now().withZone(userTimeZone.get()));

            // Start generate protobuf for alarm
            int ringOffsetFromNowInSecond = -1;
            int ringDurationInMS = 120 * DateTimeConstants.MILLIS_PER_SECOND;
            if (this.featureFlipper.deviceFeatureActive(FeatureFlipper.RING_DURATION_FROM_CONFIG, deviceName, Collections.EMPTY_LIST)) {
                ringDurationInMS = this.ringDurationSec * DateTimeConstants.MILLIS_PER_SECOND;
            }

            if (!nextRingTime.isEmpty()) {
                ringOffsetFromNowInSecond = (int) ((nextRingTime.actualRingTimeUTC - now.getMillis()) / DateTimeConstants.MILLIS_PER_SECOND);
                if (ringOffsetFromNowInSecond < 0) {
                    // The ring time process took too much time, force the alarm take off immediately
                    ringOffsetFromNowInSecond = 1;
                }
            }

            int soundId = 0;
            if (nextRingTime.soundIds != null && nextRingTime.soundIds.length > 0) {
                soundId = (int) nextRingTime.soundIds[0];
            }
            final OutputProtos.SyncResponse.Alarm.Builder alarmBuilder = OutputProtos.SyncResponse.Alarm.newBuilder()
                    .setStartTime((int) (nextRingTime.actualRingTimeUTC / DateTimeConstants.MILLIS_PER_SECOND))
                    .setEndTime((int) ((nextRingTime.actualRingTimeUTC + ringDurationInMS) / DateTimeConstants.MILLIS_PER_SECOND))
                    .setRingDurationInSecond(ringDurationInMS / DateTimeConstants.MILLIS_PER_SECOND)
                    .setRingtoneId(soundId)
                    .setRingtonePath(Alarm.Utils.getSoundPathFromSoundId(soundId))
                    .setRingOffsetFromNowInSecond(ringOffsetFromNowInSecond);
            responseBuilder.setAlarm(alarmBuilder.build());
            responseBuilder.setRingTimeAck(String.valueOf(nextRingTime.actualRingTimeUTC));

            if(nextRingTime.fromSmartAlarm && featureFlipper.deviceFeatureActive(ServiceFeatureFlipper.PRINT_ALARM_ACK.getFeatureName(), deviceName, Collections.EMPTY_LIST)) {
                LOGGER.warn("action=print-smart-alarm sense_id={} actual_ring_time={} expected_ring_time={}", deviceName, nextRingTime.actualRingTimeUTC, nextRingTime.expectedRingTimeUTC);
            }




            // End generate protobuf for alarm

            if (featureFlipper.deviceFeatureActive(FeatureFlipper.ENABLE_OTA_UPDATES, deviceName, groups)) {
                //Perform all OTA checks and compute the update file list (if necessary)
                final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = computeOTAFileList(deviceName, groups, userTimeZone.get(), batch, userInfoList, deviceHasOutOfSyncClock, hardwareVersion);
                if (!fileDownloadList.isEmpty()) {
                    if (shouldOverrideOTA(deviceName, groups)) {
                        LOGGER.warn("action=ota-override sense_id={}", deviceName);
                        fileDownloadList.clear();
                    }
                    responseBuilder.addAllFiles(fileDownloadList);
                    responseBuilder.setResetMcu(false); //Clear the reset MCU command since in the fw it will take precedence over the OTA
                }
            }



            final AudioControlProtos.AudioControl.Builder audioControl = AudioControlProtos.AudioControl
                    .newBuilder()
                    .setAudioCaptureAction(AudioControlProtos.AudioControl.AudioCaptureAction.ON)
                    .setAudioSaveFeatures(AudioControlProtos.AudioControl.AudioCaptureAction.OFF)
                    .setAudioSaveRawData(AudioControlProtos.AudioControl.AudioCaptureAction.OFF);

            if (featureFlipper.deviceFeatureActive(FeatureFlipper.ALWAYS_ON_AUDIO, deviceName, groups)) {
                audioControl.setAudioCaptureAction(AudioControlProtos.AudioControl.AudioCaptureAction.ON);
                audioControl.setAudioSaveFeatures(AudioControlProtos.AudioControl.AudioCaptureAction.ON);
                audioControl.setAudioSaveRawData(AudioControlProtos.AudioControl.AudioCaptureAction.ON);
            }


            //feature flip SENSE_UPLOADS_KEYWORD_FEATURES controls if Sense uploads the keyword features or not
            final AudioFeaturesControlProtos.AudioFeaturesControl.Builder audioFeaturesControl =
                    AudioFeaturesControlProtos.AudioFeaturesControl.newBuilder().setEnableKeywordFeatures(
                                    featureFlipper.deviceFeatureActive(ServiceFeatureFlipper.SENSE_UPLOADS_KEYWORD_FEATURES.getFeatureName(), deviceName, groups));



            final Boolean isIncreasedInterval = featureFlipper.deviceFeatureActive(FeatureFlipper.INCREASE_UPLOAD_INTERVAL, deviceName, groups);
            final int uploadCycle = computeNextUploadInterval(nextRingTime, now, senseUploadConfiguration, isIncreasedInterval);
            responseBuilder.setBatchSize(uploadCycle);

            if (shouldWriteRingTimeHistory(now, nextRingTime, responseBuilder.getBatchSize())) {
                this.ringTimeHistoryDAODynamoDB.setNextRingTime(deviceName, userInfoList, nextRingTime);
            }


            //Log to a kinesis stream an alarm action if within the Alarm Actions window (default 60 mins)
            if(shouldLogAlarmActions(now, nextRingTime, ALARM_ACTIONS_WINDOW_MINS)) {
                final DataLogger alarmActionsLogger = kinesisLoggerFactory.get(QueueName.ALARM_ACTIONS);
                for(final AlarmExpansion expansion : nextRingTime.expansions){
                    final ExpansionProtos.AlarmAction.Builder alarmActionBuilder = ExpansionProtos.AlarmAction.newBuilder()
                        .setDeviceId(deviceName)
                        .setUnixTime(now.getMillis() / 1000)
                        .setServiceType(ExpansionProtos.ServiceType.valueOf(expansion.serviceName))
                        .setExpectedRingtimeUtc(nextRingTime.expectedRingTimeUTC)
                        .setTargetValueMin(expansion.targetValue.min)
                        .setTargetValueMax(expansion.targetValue.max);

                    if(expansion.enabled) {
                        LOGGER.info("action=kinesis-alarm-action-put sense_id={} expansion_id={}", deviceName, expansion.id);
                        alarmActionsLogger.put(deviceName, alarmActionBuilder.build().toByteArray());
                    }
                }

            }

            LOGGER.debug("{} batch size set to {}", deviceName, responseBuilder.getBatchSize());
            setPillColors(userInfoList, responseBuilder);
            responseBuilder.setAudioControl(audioControl);
            responseBuilder.setAudioFeaturesControl(audioFeaturesControl);

        } else {
            LOGGER.error("error=no-timezone message=default-utc-for-ota sense_id={} ip_address={}", deviceName, ipAddress);
            if (featureFlipper.deviceFeatureActive(FeatureFlipper.ENABLE_OTA_UPDATES, deviceName, groups)) {
                final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = computeOTAFileList(deviceName, groups, DateTimeZone.UTC, batch, userInfoList, deviceHasOutOfSyncClock, hardwareVersion);
                if (!fileDownloadList.isEmpty()) {
                    if (shouldOverrideOTA(deviceName, groups)) {
                        LOGGER.info("action=ota_override sense_id={}", deviceName);
                        fileDownloadList.clear();
                    }
                    responseBuilder.addAllFiles(fileDownloadList);
                    responseBuilder.setResetMcu(false);
                }
            }
        }

        if (featureFlipper.deviceFeatureActive(FeatureFlipper.ALLOW_RESPONSE_COMMANDS, deviceName, groups)) {
            addCommandsToResponse(deviceName, firmwareVersion, responseBuilder);
        }

        if (responseBuilder.getFilesCount() > 0) {
            otaFileResponses.mark();
        }

        final OutputProtos.SyncResponse syncResponse = responseBuilder.build();

        LOGGER.debug("Len pb = {}", syncResponse.toByteArray().length);

        final Optional<byte[]> signedResponse = SignedMessage.sign(syncResponse.toByteArray(), encryptionKey);
        if (!signedResponse.isPresent()) {
            LOGGER.error("Failed signing message");
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }

        final int responseLength = signedResponse.get().length;
        if (responseLength > 2048) {
            LOGGER.warn("error=response-size-too-large size={} sense_id={}", responseLength, deviceName);
        }

        return signedResponse.get();
    }

    public boolean shouldOverrideOTA(final String deviceId, final List<String> groups) {
        if (!featureFlipper.deviceFeatureActive(FeatureFlipper.SLEEP_SOUNDS_OVERRIDE_OTA, deviceId, groups)) {
            return false;
        }

        return isAudioPlaying(deviceId);
    }

    public boolean isAudioPlaying(final String deviceId) {

        Optional<SenseStateAtTime> senseState = senseStateDynamoDB.getState(deviceId);
        if(!senseState.isPresent()) {
            LOGGER.error("error=no_sense_state sense_id={}", deviceId);
            return false;
        }
        
        final State.SenseState state = senseState.get().state;
        if(!state.hasAudioState()) {
            LOGGER.error("error=no_audio_state sense_id={}", deviceId);
            return false;
        }

        return state.getAudioState().getPlayingAudio();
    }

    public static boolean shouldWriteRingTimeHistory(final DateTime now, final RingTime nextRingTime, final int uploadIntervalInMinutes) {
        return now.plusMinutes(uploadIntervalInMinutes).isBefore(nextRingTime.actualRingTimeUTC) == false &&  // now + upload_cycle >= next_ring
                now.isAfter(nextRingTime.actualRingTimeUTC) == false &&
                nextRingTime.isEmpty() == false;
    }

    public static boolean shouldLogAlarmActions(final DateTime now, final RingTime nextRingTime, final Integer actionTimeBufferMins) {
        return now.plusMinutes(actionTimeBufferMins).isAfter(nextRingTime.actualRingTimeUTC) &&
            !now.isAfter(nextRingTime.actualRingTimeUTC) &&
            !nextRingTime.isEmpty() &&
            nextRingTime.expansions != null &&
            !nextRingTime.expansions.isEmpty();
    }

    public static int computeNextUploadInterval(final RingTime nextRingTime, final DateTime now, final SenseUploadConfiguration senseUploadConfiguration, final Boolean isIncreasedInterval){

        int uploadInterval = 1;
        final Long userNextAlarmTimestamp = nextRingTime.expectedRingTimeUTC; // This must be expected time, not actual.
        // Alter upload cycles based on date-time
        uploadInterval = UploadSettings.computeUploadIntervalPerUserPerSetting(now, senseUploadConfiguration, isIncreasedInterval);

        // Boost upload cycle based on expected alarm deadline.
        final Integer adjustedUploadInterval = UploadSettings.adjustUploadIntervalInMinutes(now.getMillis(), uploadInterval, userNextAlarmTimestamp);
        if (adjustedUploadInterval < uploadInterval) {
            uploadInterval = adjustedUploadInterval;
        }

        // Prolong upload cycle so Sense can safely pass ring time
        uploadInterval = computePassRingTimeUploadInterval(nextRingTime, now, uploadInterval);

        /*if(uploadInterval > senseUploadConfiguration.getLongInterval()){
            uploadInterval = senseUploadConfiguration.getLongInterval();
        }*/

        return uploadInterval;
    }

    public static boolean isNextUploadCrossRingBound(final RingTime nextRingTime, final DateTime now) {
        final int ringTimeOffsetFromNowMillis = (int) (nextRingTime.actualRingTimeUTC - now.getMillis());
        return nextRingTime.isEmpty() == false &&
                ringTimeOffsetFromNowMillis <= 2 * DateTimeConstants.MILLIS_PER_MINUTE &&
                ringTimeOffsetFromNowMillis > 0;
    }

    public static int computePassRingTimeUploadInterval(final RingTime nextRingTime, final DateTime now, final int adjustedUploadCycle) {
        final int ringTimeOffsetFromNowMillis = (int) (nextRingTime.actualRingTimeUTC - now.getMillis());
        if (isNextUploadCrossRingBound(nextRingTime, now)) {
            //If an alarm will ring in the next 2 minutes, push the batch upload out 2 mins after the alarm ring time.
            final int uploadCycleThatPassRingTime = ringTimeOffsetFromNowMillis / DateTimeConstants.MILLIS_PER_MINUTE + 2;
            return uploadCycleThatPassRingTime;
        }

        return adjustedUploadCycle;
    }


    @POST
    @Path("/pill")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] onPillBatchProtobufReceived(final byte[] body) {

        String senseId = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if (senseId == null) {
            senseId = "";
        }

        final SignedMessage signedMessage = SignedMessage.parse(body);
        final String ipAddress = getIpAddress(request);
        SenseCommandProtos.batched_pill_data batchPilldata = null;
        try {
            batchPilldata = SenseCommandProtos.batched_pill_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            LOGGER.error("error=protobuf-parsing-failed sense_id={} ip_address={} message={}", senseId, ipAddress, exception.getMessage());
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }
        LOGGER.debug("Received for pill protobuf message {}", TextFormat.shortDebugString(batchPilldata));


        final Optional<byte[]> optionalKeyBytes = keyStore.get(batchPilldata.getDeviceId());
        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("error=keystore-get-failed sense_id={} ip_address={}", batchPilldata.getDeviceId(), ipAddress);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }
        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("error=signature-failed sense_id={} ip_address={} message={}", batchPilldata.getDeviceId(), ipAddress, error.get().message);
            return plainTextError(Response.Status.UNAUTHORIZED, "");
        }

        final SenseCommandProtos.batched_pill_data.Builder cleanBatch = SenseCommandProtos.batched_pill_data.newBuilder();
        cleanBatch.setDeviceId(batchPilldata.getDeviceId());

        final int proxCount = batchPilldata.getProxCount();
        if(proxCount > 0) {
            LOGGER.info("sense_id={} prox_count={}", batchPilldata.getDeviceId(), proxCount);
        }

        // Note: we are not checking for clock issue on prox data at the moment
        // we are just forwarding it along
        cleanBatch.addAllProx(batchPilldata.getProxList());

        for (final SenseCommandProtos.pill_data pill : batchPilldata.getPillsList()) {
            final DateTime now = DateTime.now();
            final Long pillTimestamp = pill.getTimestamp() * 1000L;

            if (pillTimestamp > now.plusHours(CLOCK_SKEW_TOLERATED_IN_HOURS).getMillis()) {
                final DateTime outOfSyncDateTime = new DateTime(pillTimestamp, DateTimeZone.UTC);
                LOGGER.warn("Pill data timestamp from {} is too much in the future. now = {}, timestamp = {}",
                        pill.getDeviceId(),
                        now,
                        outOfSyncDateTime);
                pillClockOutOfSync.mark(1);

                // This is intended to check for very specific clock bugs from Sense on 10/01/2015
                if (featureFlipper.deviceFeatureActive(FeatureFlipper.ATTEMPT_TO_CORRECT_PILL_REPORTED_TIMESTAMP, pill.getDeviceId(), Collections.EMPTY_LIST)) {
                    final Optional<Long> optionalCorrectedTimestamp = correctForPillClockSkewBug(outOfSyncDateTime, now);
                    LOGGER.warn("Attempt to correct pill data timestamp for {}", pill.getDeviceId());
                    if (optionalCorrectedTimestamp.isPresent()) {
                        // add pill data with corrected timestamp to Kinesis
                        final Long correctedTimestamp = optionalCorrectedTimestamp.get();
                        final SenseCommandProtos.pill_data correctedPill = SenseCommandProtos.pill_data.newBuilder(pill).
                                setTimestamp(correctedTimestamp).
                                build();

                        LOGGER.warn("Pill data timestamp corrected for {} to {}", correctedPill.getDeviceId(),
                                new DateTime(correctedPill.getTimestamp() * 1000L, DateTimeZone.UTC));

                        cleanBatch.addPills(correctedPill);
                    }
                }

                // skip saving to Kinesis regular clock skew data
                continue;
            }
            cleanBatch.addPills(pill);
        }

        // Put raw pill data into Kinesis
        final DataLogger batchDataLogger = kinesisLoggerFactory.get(QueueName.BATCH_PILL_DATA);
        batchDataLogger.put(batchPilldata.getDeviceId(), cleanBatch.build().toByteArray());


        final SenseCommandProtos.MorpheusCommand responseCommand = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PILL_DATA)
                .setVersion(0)
                .build();

        final Optional<byte[]> signedResponse = SignedMessage.sign(responseCommand.toByteArray(), optionalKeyBytes.get());
        if (!signedResponse.isPresent()) {
            LOGGER.error("Failed signing message");
            return plainTextError(Response.Status.INTERNAL_SERVER_ERROR, "");
        }

        return signedResponse.get();
    }

    public static Optional<Long> correctForPillClockSkewBug(final DateTime pillDateTime, DateTime referenceDateTime) {
        // check if pill datetime is 6 months ahead
        if (pillDateTime.isAfter(referenceDateTime.plusHours(CLOCK_BUG_SKEW_IN_HOURS))) {
            // attempt to correct for 6 months clock skew
            final DateTime correctedDateTime = DateTimeUtil.possiblySanitizeSampleTime(referenceDateTime, pillDateTime, CLOCK_SKEW_TOLERATED_IN_HOURS);
            if (!correctedDateTime.equals(pillDateTime)) {
                return Optional.of(correctedDateTime.getMillis() / 1000L); // return data in seconds!!!
            }
        }
        return Optional.absent();
    }

    private Optional<DateTimeZone> getUserTimeZone(List<UserInfo> userInfoList) {
        for (final UserInfo info : userInfoList) {
            if (info.timeZone.isPresent()) {
                return info.timeZone;
            }
        }
        return Optional.absent();
    }


    /**
     * Maps account to timezones
     * @param userInfoList
     * @return
     */
    private Map<Long, DateTimeZone> getUserTimeZones(final List<UserInfo> userInfoList) {
        final Map<Long, DateTimeZone> map = Maps.newHashMap();
        for (final UserInfo info : userInfoList) {
            if (info.timeZone.isPresent()) {
                map.put(info.accountId, info.timeZone.get());
            }
        }
        return ImmutableMap.copyOf(map);
    }

    /**
     * Performs all OTA availability checks and produces an update file list
     * @param deviceID
     * @param deviceGroups
     * @param userTimeZone
     * @param batchData
     * @return
     */
    private List<OutputProtos.SyncResponse.FileDownload> computeOTAFileList(final String deviceID,
                                                                            final List<String> deviceGroups,
                                                                            final DateTimeZone userTimeZone,
                                                                            final DataInputProtos.batched_periodic_data batchData,
                                                                            final List<UserInfo> userInfoList,
                                                                            final Boolean hasOutOfSyncClock,
                                                                            final HardwareVersion hardwareVersion) {
        final String currentFirmwareVersion = Integer.toString(batchData.getFirmwareVersion());
        final int uptimeInSeconds = (batchData.hasUptimeInSecond()) ? batchData.getUptimeInSecond() : -1;
        final DateTime currentDTZ = DateTime.now().withZone(userTimeZone);
        final DateTime startOTAWindow = new DateTime(userTimeZone).withHourOfDay(otaConfiguration.getStartUpdateWindowHour()).withMinuteOfHour(0).withSecondOfMinute(0);
        final DateTime endOTAWindow = new DateTime(userTimeZone).withHourOfDay(otaConfiguration.getEndUpdateWindowHour()).withMinuteOfHour(0).withSecondOfMinute(0);
        final Integer deviceUptimeDelay = otaConfiguration.getDeviceUptimeDelay();
        Boolean bypassOTAChecks = (featureFlipper.deviceFeatureActive(FeatureFlipper.BYPASS_OTA_CHECKS, deviceID, deviceGroups));
        final String ipAddress = getIpAddress(request);

        // Allow special handling for devices coming from factory on 0.9.22_rc7 with the clock sync issue
        if (hasOutOfSyncClock && currentFirmwareVersion.equals(FW_VERSION_0_9_22_RC7)) {
            Integer pillCount = 0;
            for (final UserInfo userInfo : userInfoList) {
                if (userInfo.pillColor.isPresent()) {
                    pillCount++;
                }
            }
            if (pillCount > 1 || uptimeInSeconds > (CLOCK_SYNC_SPECIAL_OTA_UPTIME_MINS * DateTimeConstants.SECONDS_PER_MINUTE)) {
                if (!deviceGroups.isEmpty()) {
                    final String updateGroup = deviceGroups.get(0);
                    final SenseFirmwareUpdateQuery senseFirmwareUpdateQuery = SenseFirmwareUpdateQuery.forSense(deviceID, updateGroup, currentFirmwareVersion, hardwareVersion);
                    LOGGER.warn("Clock Sync OTA Override for DeviceId {} with Group {}", deviceID, updateGroup);
                    final FirmwareUpdate firmwareUpdate = firmwareUpdateStore.getFirmwareUpdate(senseFirmwareUpdateQuery);
                    return firmwareUpdate.files;
                } else {
                    if (featureFlipper.deviceFeatureActive(FeatureFlipper.OTA_RELEASE, deviceID, deviceGroups)) {
                        LOGGER.warn("Clock Sync OTA Override for DeviceId {} with no group", deviceID);
                        final SenseFirmwareUpdateQuery senseFirmwareUpdateQuery = SenseFirmwareUpdateQuery.forSense(deviceID, FeatureFlipper.OTA_RELEASE, currentFirmwareVersion, hardwareVersion);
                        final FirmwareUpdate firmwareUpdate = firmwareUpdateStore.getFirmwareUpdate(senseFirmwareUpdateQuery);
                        return firmwareUpdate.files;
                    }
                }
            }
            return Collections.emptyList();
        }

        //Devices on a fw version that requires upgrade and have the response command for forced ota set should bypass OTA checks
        if ((featureFlipper.deviceFeatureActive(FeatureFlipper.FW_VERSIONS_REQUIRING_UPDATE, currentFirmwareVersion, Collections.EMPTY_LIST))) {
            final Map<ResponseCommand, String> commandMap = responseCommandsDAODynamoDB.getResponseCommands(
                deviceID,
                Integer.valueOf(currentFirmwareVersion),
                Lists.newArrayList(ResponseCommand.FORCE_OTA));
            if(!commandMap.isEmpty() && commandMap.containsKey(ResponseCommand.FORCE_OTA)) {
                LOGGER.info("action=force-ota device_id={}", deviceID);
                bypassOTAChecks = true;
            }
        }

        // Primary OTA code path
        final boolean canOTA = OTAProcessor.canDeviceOTA(
                deviceID,
                deviceGroups,
                new HashSet<String>(), // to be removed entirely
                deviceUptimeDelay,
                uptimeInSeconds,
                currentDTZ,
                startOTAWindow,
                endOTAWindow,
                bypassOTAChecks,
                ipAddress);

        if (canOTA) {

            // groups take precedence over feature
            if (!deviceGroups.isEmpty()) {
                final String updateGroup = deviceGroups.get(0);
                LOGGER.debug("DeviceId {} belongs to groups: {}", deviceID, deviceGroups);
                final SenseFirmwareUpdateQuery senseFirmwareUpdateQuery = SenseFirmwareUpdateQuery.forSense(deviceID, updateGroup, currentFirmwareVersion, hardwareVersion);
                final FirmwareUpdate firmwareUpdate = firmwareUpdateStore.getFirmwareUpdate(senseFirmwareUpdateQuery);
                return firmwareUpdate.files;
            } else {
                // This feature flipper can disable OTA for all groups and all devices if set to 0%
                if (featureFlipper.deviceFeatureActive(FeatureFlipper.OTA_RELEASE, deviceID, deviceGroups)) {
                    LOGGER.debug("Feature 'release' is active for device: {}", deviceID);
                    final SenseFirmwareUpdateQuery senseFirmwareUpdateQuery = SenseFirmwareUpdateQuery.forSense(deviceID, FeatureFlipper.OTA_RELEASE, currentFirmwareVersion, hardwareVersion);
                    final FirmwareUpdate firmwareUpdate = firmwareUpdateStore.getFirmwareUpdate(senseFirmwareUpdateQuery);
                    return firmwareUpdate.files;
                }
            }
        }
        return Collections.emptyList();
    }

    public Optional<byte[]> getKey(String deviceId, List<String> groups, String ipAddress) {

        if (KeyStoreDynamoDB.DEFAULT_FACTORY_DEVICE_ID.equals(deviceId) &&
                featureFlipper.deviceFeatureActive(FeatureFlipper.OFFICE_ONLY_OVERRIDE, deviceId, groups)) {
            if (ipAddress.equals(LOCAL_OFFICE_IP_ADDRESS)) {
                return keyStore.get(deviceId);
            } else {
                return keyStore.getStrict(deviceId);
            }
        }
        return keyStore.get(deviceId);

    }

    private void addCommandsToResponse(final String deviceName, final Integer firmwareVersion, final OutputProtos.SyncResponse.Builder responseBuilder) {

        LOGGER.info("Response commands allowed for DeviceId: {}", deviceName);
        //Create a list of SyncResponse commands to be fetched from DynamoDB for a given device & firmware
        final List<ResponseCommand> respCommandsToFetch = Lists.newArrayList(
                ResponseCommand.RESET_TO_FACTORY_FW,
                ResponseCommand.RESET_MCU,
                ResponseCommand.SET_LOG_LEVEL
        );

        final Map<ResponseCommand, String> commandMap = responseCommandsDAODynamoDB.getResponseCommands(deviceName, firmwareVersion, respCommandsToFetch);

        if (commandMap.isEmpty()) {
            return;
        }

        //Process and inject commands
        for (final ResponseCommand cmd : respCommandsToFetch) {
            if (!commandMap.containsKey(cmd)) {
                continue;
            }

            final String cmdValue = commandMap.get(cmd);

            switch (cmd) {
                case RESET_TO_FACTORY_FW:
                    responseBuilder.setResetToFactoryFw(Boolean.parseBoolean(cmdValue));
                    break;
                case RESET_MCU:
                    responseBuilder.setResetMcu(Boolean.parseBoolean(cmdValue));
                    break;
                case SET_LOG_LEVEL:
                    try {
                        final Integer logLevel = Integer.parseInt(cmdValue);
                        if (!SenseLogLevelUtil.isAllowedLogLevel(logLevel)) {
                            LOGGER.warn("Log level value '%03X' not allowed.", logLevel);
                            continue;
                        }
                        responseBuilder.setUploadLogLevel(logLevel);
                    } catch (NumberFormatException nfe) {
                        LOGGER.warn("Bad log level value passed with 'set_log_level' response command.");
                        return;
                    }
                    break;
            }
        }
    }

    public static boolean isClockOutOfSync(final DateTime sampleTime, final DateTime referenceTime, final Integer offsetThreshold) {
        return sampleTime.isAfter(referenceTime.plusHours(offsetThreshold)) || sampleTime.isBefore(referenceTime.minusHours(offsetThreshold));
    }
}
