package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.api.input.State;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.firmware.FirmwareUpdate;
import com.hello.suripu.core.firmware.FirmwareUpdateStore;
import com.hello.suripu.core.firmware.SenseFirmwareUpdateQuery;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.models.Alarm;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.Util;
import com.hello.suripu.service.configuration.OTAConfiguration;
import com.librato.rollout.RolloutClient;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Created by jnorgan on 10/14/15.
 */

public class ReceiveResourceIT extends ResourceTest {
    private static final String SENSE_ID = "fake-sense";
    private static final byte[] KEY = "1234567891234567".getBytes();
    private static final String FIRMWARE_VERSION = "12345678";
    private static final String FW_VERSION_0_9_22_RC7 = "1530439804";
    private static final Integer FUTURE_UNIX_TIMESTAMP = 2139176514; //14 Oct 2037 23:41:54 GMT
    private List<UserInfo> userInfoList;
    private List<OutputProtos.SyncResponse.FileDownload> fileList;
    private DateTimeZone userTimeZone;
    private ReceiveResource receiveResource;


    @Before
    public void setUp() {
        super.setUp();

        BaseResourceTestHelper.kinesisLoggerFactoryStubGet(kinesisLoggerFactory, QueueName.LOGS, dataLogger);
        BaseResourceTestHelper.kinesisLoggerFactoryStubGet(kinesisLoggerFactory, QueueName.SENSE_SENSORS_DATA, dataLogger);

        final ReceiveResource receiveResource = new ReceiveResource(
                keyStore,
                kinesisLoggerFactory,
                mergedUserInfoDynamoDB,
                ringTimeHistoryDAODynamoDB,
                true,
                firmwareUpdateStore,
                groupFlipper,
                senseUploadConfiguration,
                otaConfiguration,
                responseCommandsDAODynamoDB,
                240,
                calibrationDAO,
                metricRegistry,
                senseStateDynamoDB,
                fileSynchronizer,
                senseEventsDAO
        );
        receiveResource.request = httpServletRequest;
        receiveResource.featureFlipper = featureFlipper;
        receiveResource.senseClockOutOfSync = meter;
        receiveResource.senseClockOutOfSync3h = meter;
        receiveResource.pillClockOutOfSync = meter;
        receiveResource.otaFileResponses = meter;
        receiveResource.filesMarkedForDownload = meter;
        this.receiveResource = spy(receiveResource);

        BaseResourceTestHelper.stubGetHeader(receiveResource.request, "X-Forwarded-For", "127.0.0.1");
        final List<Alarm> alarmList = Lists.newArrayList();
        userTimeZone = DateTimeZone.forTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));

        final UserInfo userInfo = new UserInfo(
                SENSE_ID,
                1234L,
                alarmList,
                Optional.<RingTime>absent(),
                Optional.of(userTimeZone),
                Optional.<OutputProtos.SyncResponse.PillSettings>absent(),
                12345678L);
        userInfoList = Lists.newArrayList(userInfo);

        final OutputProtos.SyncResponse.FileDownload fileDownload = OutputProtos.SyncResponse.FileDownload.newBuilder()
                .setHost("test")
                .setCopyToSerialFlash(true)
                .setResetApplicationProcessor(false)
                .setSerialFlashFilename("mcuimgx.bin")
                .setSerialFlashPath("/sys/")
                .setSdCardFilename("mcuimgx.bin")
                .setSdCardPath("/")
                .build();
        fileList = Lists.newArrayList(fileDownload);
    }

    @Test
    public void testOTAPublicRelease() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);
        stubGetUserInfo(mergedUserInfoDynamoDB, userInfoList);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.ENABLE_OTA_UPDATES, Collections.EMPTY_LIST, true);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.OTA_RELEASE, Collections.EMPTY_LIST, true);
        stubGetOTAWindowStart(otaConfiguration, 0);
        stubGetOTAWindowEnd(otaConfiguration, 23);
        stubGetPopulatedFirmwareFileListForGroup(firmwareUpdateStore, FeatureFlipper.OTA_RELEASE, FIRMWARE_VERSION, fileList);

        final long unixTime = DateTime.now().withZone(userTimeZone).getMillis() / 1000L;

        final byte[] data = receiveResource.receiveBatchSenseData(generateValidProtobufWithSignature(KEY, 3600, FIRMWARE_VERSION, (int)unixTime));

        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);
        final OutputProtos.SyncResponse syncResponse;
        try {
            syncResponse = OutputProtos.SyncResponse.parseFrom(protobufBytes);
            assertThat(syncResponse.hasResetMcu(), is(true));
            assertThat(syncResponse.getResetMcu(), is(false));
            assertThat(syncResponse.getFilesCount(), is(1));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            assertThat(true, is(false));
        }
    }

    @Test
    public void testOTAPublicReleaseOutsideWindow() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);
        stubGetUserInfo(mergedUserInfoDynamoDB, userInfoList);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.ENABLE_OTA_UPDATES, Collections.EMPTY_LIST, true);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.OTA_RELEASE, Collections.EMPTY_LIST, true);
        stubGetOTAWindowStart(otaConfiguration, 23);
        stubGetOTAWindowEnd(otaConfiguration, 23);
        stubGetPopulatedFirmwareFileListForGroup(firmwareUpdateStore, FeatureFlipper.OTA_RELEASE, FIRMWARE_VERSION, fileList);

        final long unixTime = DateTime.now().withZone(userTimeZone).getMillis() / 1000L;

        final byte[] data = receiveResource.receiveBatchSenseData(generateValidProtobufWithSignature(KEY, 3600, FIRMWARE_VERSION, (int)unixTime));

        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);
        final OutputProtos.SyncResponse syncResponse;
        try {
            syncResponse = OutputProtos.SyncResponse.parseFrom(protobufBytes);
            assertThat(syncResponse.hasResetMcu(), is(false));
            assertThat(syncResponse.getFilesCount(), is(0));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            assertThat(true, is(false));
        }
    }

    @Test
    public void testOTAForFactoryClockSyncIssueNoPillsHighUptime() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);
        stubGetUserInfo(mergedUserInfoDynamoDB, userInfoList);

        stubGetOTAWindowStart(otaConfiguration, 11);
        stubGetOTAWindowEnd(otaConfiguration, 20);
        stubGetPopulatedFirmwareFileListForGroup(firmwareUpdateStore, FeatureFlipper.OTA_RELEASE, FW_VERSION_0_9_22_RC7, fileList);

        final List<String> groups = Lists.newArrayList(FeatureFlipper.OTA_RELEASE);
        stubGetGroups(groupFlipper, groups);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.ENABLE_OTA_UPDATES, groups, true);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.OTA_RELEASE, groups, true);

        final byte[] data = receiveResource.receiveBatchSenseData(generateValidProtobufWithSignature(KEY, 3600, FW_VERSION_0_9_22_RC7, FUTURE_UNIX_TIMESTAMP));

        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);
        final OutputProtos.SyncResponse syncResponse;
        try {
            syncResponse = OutputProtos.SyncResponse.parseFrom(protobufBytes);
            assertThat(syncResponse.hasResetMcu(), is(true));
            assertThat(syncResponse.getResetMcu(), is(false));
            assertThat(syncResponse.getFilesCount(), is(1));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            assertThat(true, is(false));
        }
    }

    @Test
    public void testOTAForFactoryClockSyncIssueNoPillsLowUptime() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);
        stubGetUserInfo(mergedUserInfoDynamoDB, userInfoList);

        stubGetOTAWindowStart(otaConfiguration, 11);
        stubGetOTAWindowEnd(otaConfiguration, 20);
        stubGetPopulatedFirmwareFileListForGroup(firmwareUpdateStore, FeatureFlipper.OTA_RELEASE, FW_VERSION_0_9_22_RC7, fileList);

        final List<String> groups = Lists.newArrayList(FeatureFlipper.OTA_RELEASE);
        stubGetGroups(groupFlipper, groups);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.ENABLE_OTA_UPDATES, groups, true);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.OTA_RELEASE, groups, true);

        final byte[] data = receiveResource.receiveBatchSenseData(generateValidProtobufWithSignature(KEY, 899, FW_VERSION_0_9_22_RC7, FUTURE_UNIX_TIMESTAMP));

        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);
        final OutputProtos.SyncResponse syncResponse;
        try {
            syncResponse = OutputProtos.SyncResponse.parseFrom(protobufBytes);
            assertThat(syncResponse.hasResetMcu(), is(false));
            assertThat(syncResponse.getFilesCount(), is(0));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            assertThat(true, is(false));
        }
    }

    @Test
    public void testOTAForFactoryClockSyncIssueTwoPillsLowUptime() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final List<Alarm> alarmList = Lists.newArrayList();
        final OutputProtos.SyncResponse.PillSettings pillSettings = OutputProtos.SyncResponse.PillSettings.newBuilder()
                .setPillId("fake-pill")
                .setPillColor(1)
                .build();
        final UserInfo userInfo = new UserInfo(
                SENSE_ID,
                1234L,
                alarmList,
                Optional.<RingTime>absent(),
                Optional.of(userTimeZone),
                Optional.of(pillSettings),
                12345678L);
        final List<UserInfo> userInfoListPills = Lists.newArrayList(userInfo, userInfo);

        stubGetUserInfo(mergedUserInfoDynamoDB, userInfoListPills);

        stubGetOTAWindowStart(otaConfiguration, 11);
        stubGetOTAWindowEnd(otaConfiguration, 20);
        stubGetPopulatedFirmwareFileListForGroup(firmwareUpdateStore, FeatureFlipper.OTA_RELEASE, FW_VERSION_0_9_22_RC7, fileList);

        final List<String> groups = Lists.newArrayList(FeatureFlipper.OTA_RELEASE);
        stubGetGroups(groupFlipper, groups);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.ENABLE_OTA_UPDATES, groups, true);
        stubGetFeatureActive(featureFlipper, FeatureFlipper.OTA_RELEASE, groups, true);

        final byte[] data = receiveResource.receiveBatchSenseData(generateValidProtobufWithSignature(KEY, 899, FW_VERSION_0_9_22_RC7, FUTURE_UNIX_TIMESTAMP));

        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);
        final OutputProtos.SyncResponse syncResponse;
        try {
            syncResponse = OutputProtos.SyncResponse.parseFrom(protobufBytes);
            assertThat(syncResponse.hasResetMcu(), is(true));
            assertThat(syncResponse.getResetMcu(), is(false));
            assertThat(syncResponse.getFilesCount(), is(1));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            assertThat(true, is(false));
        }
    }

    @Test
    public void testUpdateFileManifest() throws Exception {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final FileSync.FileManifest requestManifest = FileSync.FileManifest.newBuilder()
                .setFirmwareVersion(1)
                .setSenseId(SENSE_ID)
                .build();

        // Response is same as request but with a file added
        final FileSync.FileManifest newManifest = FileSync.FileManifest.newBuilder(requestManifest)
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setHost("host")
                                .setUrl("url")
                                .setSdCardPath("path")
                                .setSdCardFilename("file")
                                .build())
                        .build())
                .build();

        BaseResourceTestHelper.stubSynchronizeFileManifest(fileSynchronizer, newManifest);

        final byte[] response = receiveResource.updateFileManifest(signProtobuf(requestManifest, KEY));
        final byte[] protobufBytes = Arrays.copyOfRange(response, 16 + 32, response.length);
        final FileSync.FileManifest responseManifest = FileSync.FileManifest.parseFrom(protobufBytes);
        assertThat(responseManifest, is(newManifest));
    }

    @Test(expected= WebApplicationException.class)
    public void testUpdateFileManifestValidateFirmwareVersion() throws Exception {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final FileSync.FileManifest requestManifest = FileSync.FileManifest.newBuilder()
                .setSenseId(SENSE_ID)
                .build();

        receiveResource.updateFileManifest(signProtobuf(requestManifest, KEY));
    }

    @Test
    public void testUpdateSenseState() throws Exception {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final State.SenseState senseState = State.SenseState.newBuilder()
                .setSenseId(SENSE_ID)
                .setAudioState(State.AudioState.newBuilder()
                        .setPlayingAudio(false)
                        .build())
                .build();

        final byte[] response = receiveResource.updateSenseState(signProtobuf(senseState, KEY));
        final byte[] protobufBytes = Arrays.copyOfRange(response, 16 + 32, response.length);
        final State.SenseState responseState = State.SenseState.parseFrom(protobufBytes);
        assertThat(responseState, is(senseState));
    }

    @Test(expected= WebApplicationException.class)
    public void testUpdateSenseStateNotMatchingSenseId() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final State.SenseState senseState = State.SenseState.newBuilder()
                .setSenseId("othersenseid")
                .setAudioState(State.AudioState.newBuilder()
                        .setPlayingAudio(false)
                        .build())
                .build();

        final byte[] response = receiveResource.updateSenseState(signProtobuf(senseState, KEY));
    }

    @Test(expected= WebApplicationException.class)
    public void testUpdateSenseStateMissingSenseHeader() {
        BaseResourceTestHelper.stubGetClientDetails(oAuthTokenStore, Optional.of(BaseResourceTestHelper.getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(keyStore, SENSE_ID, Optional.of(KEY));

        final State.SenseState senseState = State.SenseState.newBuilder()
                .setSenseId(SENSE_ID)
                .setAudioState(State.AudioState.newBuilder()
                        .setPlayingAudio(false)
                        .build())
                .build();

        final byte[] response = receiveResource.updateSenseState(signProtobuf(senseState, KEY));
    }


    private byte[] signProtobuf(final Message protobuf, final byte[] key) {
        final byte[] body  = protobuf.toByteArray();
        final Optional<byte[]> signedOptional = SignedMessage.sign(body, key);
        assertThat(signedOptional.isPresent(), is(true));
        final byte[] signed = signedOptional.get();
        final byte[] iv = Arrays.copyOfRange(signed, 0, 16);
        final byte[] sig = Arrays.copyOfRange(signed, 16, 16 + 32);
        final byte[] message = new byte[signed.length];
        copyTo(message, body, 0, body.length);
        copyTo(message, iv, body.length, body.length + iv.length);
        copyTo(message, sig, body.length + iv.length, message.length);
        return message;
    }

    @Test
    public void testGetFWVersionFromHeader() throws Exception {
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.TOP_FW_VERSION, "1.0.3");
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION, "11A1");

        final String topResponse = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.TOP_FW_VERSION);
        assertThat(topResponse, is("1.0.3"));

        final String middleResponse = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION);
        assertThat(middleResponse, is("4513"));

        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION, "11A1KJL");
        final String badResponse = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION);
        assertThat(badResponse, is("0"));
    }

    @Test
    public void testNullFWVersionHeader() throws Exception {
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.TOP_FW_VERSION, null);
        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION, null);
        final String response = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION);
        assertThat(response, is("0"));
        final String topResponse = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.TOP_FW_VERSION);
        assertThat(topResponse, is("0"));

        BaseResourceTestHelper.stubGetHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION, " ");
        final String emptyResponse = Util.getFWVersionFromHeader(httpServletRequest, HelloHttpHeader.MIDDLE_FW_VERSION);
        assertThat(emptyResponse, is("0"));
    }

    private byte[] generateValidProtobufWithSignature(final byte[] key, final Integer uptime, final String firmwareVersion, final Integer unixTime){

        final DataInputProtos.periodic_data data = DataInputProtos.periodic_data.newBuilder()
                .setUnixTime(unixTime)
                .build();
        final DataInputProtos.batched_periodic_data batch = DataInputProtos.batched_periodic_data.newBuilder()
                .setDeviceId(SENSE_ID)
                .setFirmwareVersion(Integer.valueOf(firmwareVersion))
                .setUptimeInSecond(uptime)
                .addData(data)
                .build();

        return signProtobuf(batch, key);
    }

    private void copyTo(final byte[] dest, final byte[] src, final int start, final int end){
        for(int i = start; i < end; i++){
            dest[i] = src[i-start];
        }

    }

    private void stubGetUserInfo (final MergedUserInfoDynamoDB mergedUserInfoDynamoDB, final List<UserInfo> returnInfoList) {
        doReturn(returnInfoList).when(mergedUserInfoDynamoDB).getInfo(SENSE_ID);
    }

    private void stubGetFeatureActive (final RolloutClient featureFlipper, final String featureName, final List<String> groups, final Boolean returnValue) {
        doReturn(returnValue).when(featureFlipper).deviceFeatureActive(featureName, SENSE_ID, groups);
    }

    private void stubGetOTAWindowStart (final OTAConfiguration otaConfiguration, final Integer hourOfDay) {
        doReturn(hourOfDay).when(otaConfiguration).getStartUpdateWindowHour();
    }

    private void stubGetOTAWindowEnd (final OTAConfiguration otaConfiguration, final Integer hourOfDay) {
        doReturn(hourOfDay).when(otaConfiguration).getEndUpdateWindowHour();
    }

    private void stubGetPopulatedFirmwareFileListForGroup (final FirmwareUpdateStore firmwareUpdateStore, final String groupName, final String firmwareVersion, final List<OutputProtos.SyncResponse.FileDownload> fileList) {
        final SenseFirmwareUpdateQuery query = SenseFirmwareUpdateQuery.forSenseOne(SENSE_ID, groupName, firmwareVersion);
        final FirmwareUpdate update = FirmwareUpdate.create(firmwareVersion, fileList);
        doReturn(update).when(firmwareUpdateStore).getFirmwareUpdate(query);
    }

    private void stubGetGroups (final GroupFlipper groupFlipper, final List<String> groups) {
        doReturn(groups).when(groupFlipper).getGroups(SENSE_ID);
    }
}
