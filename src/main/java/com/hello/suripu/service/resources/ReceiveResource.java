package com.hello.suripu.service.resources;

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
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.ResponseCommandsDAODynamoDB;
import com.hello.suripu.core.db.ResponseCommandsDAODynamoDB.ResponseCommand;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.firmware.FirmwareUpdateStore;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.hello.suripu.core.models.Alarm;
import com.hello.suripu.core.models.Calibration;
import com.hello.suripu.core.models.CurrentRoomState;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.processors.OTAProcessor;
import com.hello.suripu.core.processors.RingProcessor;
import com.hello.suripu.core.resources.BaseResource;
import com.hello.suripu.core.util.DateTimeUtil;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.core.util.RoomConditionUtil;
import com.hello.suripu.core.util.SenseLogLevelUtil;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.configuration.OTAConfiguration;
import com.hello.suripu.service.configuration.SenseUploadConfiguration;
import com.hello.suripu.service.models.UploadSettings;
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
    private static final String LOCAL_OFFICE_IP_ADDRESS = "199.87.82.114";
    private static final Integer FW_VERSION_0_9_22_RC7 = 1530439804;
    private static final Integer CLOCK_SYNC_SPECIAL_OTA_UPTIME_MINS = 15;
    private static final String FIRMWARE_DEFAULT = "0";
    private final int ringDurationSec;

    private final KeyStore keyStore;
    private final MergedUserInfoDynamoDB mergedInfoDynamoDB;
    private final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB;

    private final KinesisLoggerFactory kinesisLoggerFactory;
    private final Boolean debug;

    private final FirmwareUpdateStore firmwareUpdateStore;
    private final GroupFlipper groupFlipper;
    private final SenseUploadConfiguration senseUploadConfiguration;
    private final OTAConfiguration otaConfiguration;
    private final ResponseCommandsDAODynamoDB responseCommandsDAODynamoDB;

    private final MetricRegistry metrics;
    protected Meter senseClockOutOfSync;
    protected Meter pillClockOutOfSync;
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
                           final MetricRegistry metricRegistry) {

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
        this.pillClockOutOfSync = metrics.meter(name(ReceiveResource.class, "pill-clock-out-sync"));
        this.ringDurationSec = ringDurationSec;
        this.calibrationDAO = calibrationDAO;
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

        String topFW = FIRMWARE_DEFAULT;
        String middleFW = FIRMWARE_DEFAULT;

        try {
            topFW = (this.request.getHeader(HelloHttpHeader.TOP_FW_VERSION) != null) ? this.request.getHeader(HelloHttpHeader.TOP_FW_VERSION) : FIRMWARE_DEFAULT;
        } catch (Exception ex) {
            LOGGER.error("error=header-invalid-fwversion-top sense_id={}", debugSenseId);
        }

        try {
            middleFW = (this.request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION) != null) ? this.request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION) : FIRMWARE_DEFAULT;
        } catch (Exception ex) {
            LOGGER.error("error=header-invalid-fwversion-middle sense_id={}", debugSenseId);
        }

        LOGGER.debug("DebugSenseId device_id = {}", debugSenseId);

        try {
            data = DataInputProtos.batched_periodic_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf for deviceId = %s : %s", debugSenseId, exception.getMessage());
            LOGGER.error(errorMessage);
            return plainTextError(Response.Status.BAD_REQUEST, "bad request");
        }
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));


        LOGGER.debug("Received valid protobuf {}", data.toString());
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));

        if (!data.hasDeviceId() || data.getDeviceId().isEmpty()) {
            LOGGER.error("error=empty-device-id");
            return plainTextError(Response.Status.BAD_REQUEST, "empty device id");
        }


        final String deviceId = data.getDeviceId();
        final List<String> groups = groupFlipper.getGroups(deviceId);
        final String ipAddress = getIpAddress(request);
        final List<String> ipGroups = groupFlipper.getGroups(ipAddress);


        if (featureFlipper.deviceFeatureActive(FeatureFlipper.PRINT_RAW_PB, deviceId, groups)) {
            LOGGER.debug("RAW_PB for device_id={} {}", deviceId, Hex.encodeHexString(body));
        }

        if (OTAProcessor.isPCH(ipAddress, ipGroups) && !(featureFlipper.deviceFeatureActive(FeatureFlipper.PCH_SPECIAL_OTA, deviceId, groups))) {
            // return 202 to not confuse provisioning script with correct test key
            LOGGER.info("IP {} is from PCH. Return HTTP 202", ipAddress);
            return plainTextError(Response.Status.ACCEPTED, "");
        }

        final Optional<byte[]> optionalKeyBytes = getKey(deviceId, groups, ipAddress);

        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("error=key-store-failure sense_id={}", data.getDeviceId());
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("{} sense_id={}", error.get().message, deviceId);
            return plainTextError(Response.Status.UNAUTHORIZED, "");
        }


        final List<UserInfo> userInfoList = new ArrayList<>();

        try {
            userInfoList.addAll(this.mergedInfoDynamoDB.getInfo(data.getDeviceId()));  // get alarm related info from DynamoDB "cache".
        } catch (Exception ex) {
            LOGGER.error("error=merge-info-retrieve-failure sense_id={}: {}", data.getDeviceId(), ex.getMessage());
        }
        LOGGER.debug("Found {} pairs for device_id = {}", userInfoList.size(), data.getDeviceId());

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
        return generateSyncResponse(tempSenseId, data.getFirmwareVersion(), optionalKeyBytes.get(), data, userInfoList, ipAddress);
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
                                        final String ipAddress) {
        // TODO: Warning, since we query dynamoDB based on user input, the user can generate a lot of
        // requests to break our bank(Assume that Dynamo DB never goes down).
        // May be we should somehow cache these data to reduce load & cost.

        final OutputProtos.SyncResponse.Builder responseBuilder = OutputProtos.SyncResponse.newBuilder();

        final List<String> groups = groupFlipper.getGroups(deviceName);
        Boolean deviceHasOutOfSyncClock = false;

        for (int i = 0; i < batch.getDataCount(); i++) {
            final DataInputProtos.periodic_data data = batch.getData(i);
            final Long timestampMillis = data.getUnixTime() * 1000L;
            final DateTime roundedDateTime = new DateTime(timestampMillis, DateTimeZone.UTC).withSecondOfMinute(0);

            if (featureFlipper.deviceFeatureActive(FeatureFlipper.MEASURE_CLOCK_DRIFT, deviceName, groups)) {
                final int drift = Minutes.minutesBetween(DateTime.now(DateTimeZone.UTC), roundedDateTime).getMinutes();
                if(Math.abs(drift) >= CLOCK_DRIFT_MEASUREMENT_THRESHOLD) {
                    LOGGER.warn("action=measure-clock-drift drift={} sense_id={} number_samples={} fw_version={} ip_address={}",
                            drift,
                            deviceName,
                            batch.getDataCount(),
                            batch.getFirmwareVersion(),
                            ipAddress
                    );
                }
            }

            if (roundedDateTime.isAfter(DateTime.now().plusHours(CLOCK_SKEW_TOLERATED_IN_HOURS)) || roundedDateTime.isBefore(DateTime.now().minusHours(CLOCK_SKEW_TOLERATED_IN_HOURS))) {
                LOGGER.error("The clock for device {} is not within reasonable bounds (2h), current time = {}, received time = {}",
                        deviceName,
                        DateTime.now(),
                        roundedDateTime
                );

                LOGGER.error("error=clock-out-of-sync sense_id={} current_time={} received_time={} fw_version={} ip_address={}",
                        deviceName,
                        DateTime.now(),
                        roundedDateTime,
                        batch.getFirmwareVersion(),
                        ipAddress);

                // TODO: throw exception?
                senseClockOutOfSync.mark(1);
                deviceHasOutOfSyncClock = true;

                // TODO: pull firmware version dynamically
                final Set<Integer> fwVersionsToRebootIfClockOutOfSync = Sets.newHashSet(
                    1425228832,  // 1.0.5.2
                    510963780,   //1.0.5.3.1
                    782503713   //1.0.5.3.4
                );
                final boolean isLatestFirmware = batch.hasFirmwareVersion() && fwVersionsToRebootIfClockOutOfSync.contains(batch.getFirmwareVersion());
                if (featureFlipper.deviceFeatureActive(FeatureFlipper.REBOOT_CLOCK_OUT_OF_SYNC_DEVICES, deviceName, groups) && isLatestFirmware) {
                    LOGGER.warn("Reset MCU set for sense {}", deviceName);
                    responseBuilder.setResetMcu(true);
                } else {
                    continue;
                }
            }

            // only compute the state for the most recent conditions

            if (i == batch.getDataCount() - 1) {
                final Optional<Calibration> calibrationOptional = this.hasCalibrationEnabled(deviceName) ? calibrationDAO.get(deviceName) : Optional.<Calibration>absent();

                final CurrentRoomState currentRoomState = CurrentRoomState.fromRawData(data.getTemperature(), data.getHumidity(), data.getDustMax(), data.getLight(), data.getAudioPeakBackgroundEnergyDb(), data.getAudioPeakDisturbanceEnergyDb(),
                        roundedDateTime.getMillis(),
                        data.getFirmwareVersion(),
                        DateTime.now(),
                        2,
                        calibrationOptional);

                if (featureFlipper.deviceFeatureActive(FeatureFlipper.NEW_ROOM_CONDITION, deviceName, groups)) {
                    final Boolean hasCalibration = featureFlipper.deviceFeatureActive(FeatureFlipper.CALIBRATION, deviceName, groups);
                    final CurrentRoomState.State.Condition roomConditions = RoomConditionUtil.getGeneralRoomConditionV2(currentRoomState, hasCalibration && calibrationOptional.isPresent());
                    final CurrentRoomState.State.Condition roomConditionsLightsOff = RoomConditionUtil.getRoomConditionV2LightOff(currentRoomState, hasCalibration && calibrationOptional.isPresent());
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


        if (userTimeZone.isPresent()) {
            final RingTime nextRingTime = RingProcessor.getNextRingTimeForSense(deviceName, userInfoList, DateTime.now());

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
            // End generate protobuf for alarm

            if (featureFlipper.deviceFeatureActive(FeatureFlipper.ENABLE_OTA_UPDATES, deviceName, groups)) {
                //Perform all OTA checks and compute the update file list (if necessary)
                final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = computeOTAFileList(deviceName, groups, userTimeZone.get(), batch, userInfoList, deviceHasOutOfSyncClock);
                if (!fileDownloadList.isEmpty()) {
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

            final Boolean isIncreasedInterval = featureFlipper.deviceFeatureActive(FeatureFlipper.INCREASE_UPLOAD_INTERVAL, deviceName, groups);
            final int uploadCycle = computeNextUploadInterval(nextRingTime, now, senseUploadConfiguration, isIncreasedInterval);
            responseBuilder.setBatchSize(uploadCycle);

            if (shouldWriteRingTimeHistory(now, nextRingTime, responseBuilder.getBatchSize())) {
                this.ringTimeHistoryDAODynamoDB.setNextRingTime(deviceName, userInfoList, nextRingTime);
            }

            LOGGER.debug("{} batch size set to {}", deviceName, responseBuilder.getBatchSize());
            responseBuilder.setAudioControl(audioControl);
            setPillColors(userInfoList, responseBuilder);
        } else {
            LOGGER.error("NO TIMEZONE IS A BIG DEAL. Defaulting to UTC for OTA purposes.");
            if (featureFlipper.deviceFeatureActive(FeatureFlipper.ENABLE_OTA_UPDATES, deviceName, groups)) {
                final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = computeOTAFileList(deviceName, groups, DateTimeZone.UTC, batch, userInfoList, deviceHasOutOfSyncClock);
                if (!fileDownloadList.isEmpty()) {
                    responseBuilder.addAllFiles(fileDownloadList);
                    responseBuilder.setResetMcu(false);
                }
            }
        }

        if (featureFlipper.deviceFeatureActive(FeatureFlipper.ALLOW_RESPONSE_COMMANDS, deviceName, groups)) {
            addCommandsToResponse(deviceName, firmwareVersion, responseBuilder);
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
            LOGGER.warn("response_size too large ({}) for device_id= {}", responseLength, deviceName);
        }

        return signedResponse.get();
    }

    public static boolean shouldWriteRingTimeHistory(final DateTime now, final RingTime nextRingTime, final int uploadIntervalInMinutes) {
        return now.plusMinutes(uploadIntervalInMinutes).isBefore(nextRingTime.actualRingTimeUTC) == false &&  // now + upload_cycle >= next_ring
                now.isAfter(nextRingTime.actualRingTimeUTC) == false &&
                nextRingTime.isEmpty() == false;
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
        final SignedMessage signedMessage = SignedMessage.parse(body);
        SenseCommandProtos.batched_pill_data batchPilldata = null;

        try {
            batchPilldata = SenseCommandProtos.batched_pill_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }
        LOGGER.debug("Received for pill protobuf message {}", TextFormat.shortDebugString(batchPilldata));


        final Optional<byte[]> optionalKeyBytes = keyStore.get(batchPilldata.getDeviceId());
        if (!optionalKeyBytes.isPresent()) {
            LOGGER.error("Failed to get key from key store for device_id = {}", batchPilldata.getDeviceId());
            return plainTextError(Response.Status.BAD_REQUEST, "");
        }
        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if (error.isPresent()) {
            LOGGER.error("Failed validating signature with key: {}", error.get().message);
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
                                                                            final Boolean hasOutOfSyncClock) {
        final int currentFirmwareVersion = batchData.getFirmwareVersion();
        final int uptimeInSeconds = (batchData.hasUptimeInSecond()) ? batchData.getUptimeInSecond() : -1;
        final DateTime currentDTZ = DateTime.now().withZone(userTimeZone);
        final DateTime startOTAWindow = new DateTime(userTimeZone).withHourOfDay(otaConfiguration.getStartUpdateWindowHour()).withMinuteOfHour(0).withSecondOfMinute(0);
        final DateTime endOTAWindow = new DateTime(userTimeZone).withHourOfDay(otaConfiguration.getEndUpdateWindowHour()).withMinuteOfHour(0).withSecondOfMinute(0);
        final Set<String> alwaysOTAGroups = otaConfiguration.getAlwaysOTAGroups();
        final Integer deviceUptimeDelay = otaConfiguration.getDeviceUptimeDelay();
        final Boolean bypassOTAChecks = (featureFlipper.deviceFeatureActive(FeatureFlipper.BYPASS_OTA_CHECKS, deviceID, deviceGroups));
        final String ipAddress = getIpAddress(request);


        // OTA SPECIAL CASES

        // If device is coming from PCH, immediately allow OTA
        final List<String> ipGroups = groupFlipper.getGroups(ipAddress);
        final boolean pchOTA = (featureFlipper.deviceFeatureActive(FeatureFlipper.PCH_SPECIAL_OTA, deviceID, deviceGroups) &&
                OTAProcessor.isPCH(ipAddress, ipGroups));
        if (pchOTA) {
            LOGGER.debug("PCH Special OTA for device: {}", deviceID);
            return firmwareUpdateStore.getFirmwareUpdate(deviceID, FeatureFlipper.OTA_RELEASE, currentFirmwareVersion, true);
        }

        // Allow OTA updates only to specified devices in the Hello offices
        final Boolean isOfficeDeviceWithOverride = ((featureFlipper.deviceFeatureActive(FeatureFlipper.OFFICE_ONLY_OVERRIDE, deviceID, deviceGroups) && OTAProcessor.isHelloOffice(ipAddress)));
        //Provides for an in-office override feature that allows OTA (ignores checks) provided the IP is our office IP.
        if (isOfficeDeviceWithOverride) {
            if (!deviceGroups.isEmpty()) {
                final String updateGroup = deviceGroups.get(0);
                LOGGER.info("Office OTA Override for DeviceId {}", deviceID);
                return firmwareUpdateStore.getFirmwareUpdate(deviceID, updateGroup, currentFirmwareVersion, false);
            } else {
                return Collections.emptyList();
            }
        }

        // Allow special handling for devices coming from factory on 0.9.22_rc7 with the clock sync issue
        if (hasOutOfSyncClock && currentFirmwareVersion == FW_VERSION_0_9_22_RC7) {
            Integer pillCount = 0;
            for (final UserInfo userInfo : userInfoList) {
                if (userInfo.pillColor.isPresent()) {
                    pillCount++;
                }
            }
            if (pillCount > 1 || uptimeInSeconds > (CLOCK_SYNC_SPECIAL_OTA_UPTIME_MINS * DateTimeConstants.SECONDS_PER_MINUTE)) {
                if (!deviceGroups.isEmpty()) {
                    final String updateGroup = deviceGroups.get(0);
                    LOGGER.warn("Clock Sync OTA Override for DeviceId {} with Group {}", deviceID, updateGroup);
                    return firmwareUpdateStore.getFirmwareUpdate(deviceID, updateGroup, currentFirmwareVersion, false);
                } else {
                    if (featureFlipper.deviceFeatureActive(FeatureFlipper.OTA_RELEASE, deviceID, deviceGroups)) {
                        LOGGER.warn("Clock Sync OTA Override for DeviceId {} with no group", deviceID);
                        return firmwareUpdateStore.getFirmwareUpdate(deviceID, FeatureFlipper.OTA_RELEASE, currentFirmwareVersion, false);
                    }
                }
            }
            return Collections.emptyList();
        }

        // Primary OTA code path
        final boolean canOTA = OTAProcessor.canDeviceOTA(
                deviceID,
                deviceGroups,
                ipGroups,
                alwaysOTAGroups,
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
                return firmwareUpdateStore.getFirmwareUpdate(deviceID, updateGroup, currentFirmwareVersion, false);
            } else {
                if (featureFlipper.deviceFeatureActive(FeatureFlipper.OTA_RELEASE, deviceID, deviceGroups)) {
                    LOGGER.debug("Feature 'release' is active for device: {}", deviceID);
                    return firmwareUpdateStore.getFirmwareUpdate(deviceID, FeatureFlipper.OTA_RELEASE, currentFirmwareVersion, false);
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
}
