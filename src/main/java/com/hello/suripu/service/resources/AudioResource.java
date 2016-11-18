package com.hello.suripu.service.resources;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseAsync;
import com.amazonaws.services.kinesisfirehose.model.PutRecordRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordResult;
import com.amazonaws.services.kinesisfirehose.model.Record;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.hello.dropwizard.mikkusu.helpers.AdditionalMediaTypes;
import com.hello.suripu.api.audio.EncodeProtos;
import com.hello.suripu.api.audio.FileTransfer;
import com.hello.suripu.api.audio.MatrixProtos;
import com.hello.suripu.api.audio.SimpleMatrixProtos;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.coredropwizard.resources.BaseResource;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.models.SimpleMatrix;
import com.hello.suripu.service.utils.ServiceFeatureFlipper;
import com.librato.rollout.RolloutClient;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Path("/audio")
public class AudioResource extends BaseResource {

    @Inject RolloutClient featureFlipper;

    @Context
    HttpServletRequest request;

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioResource.class);

    private final GroupFlipper groupFlipper;

    private final AmazonS3Client s3Client;
    private final String audioBucketName;
    private final AmazonKinesisFirehoseAsync audioFeaturesFirehose;
    private final DataLogger audioMetadataLogger;
    private final KeyStore keyStore;
    private final boolean debug;
    private final String audioFeaturesFirehoseStreamName;

    private final ObjectMapper objectMapper;


    public AudioResource(
            final AmazonS3Client s3Client,
            final String audioBucketName,
            final AmazonKinesisFirehoseAsync audioFeaturesFirehose,
            final String audioFeaturesFirehoseStreamName,
            final boolean debug,
            final DataLogger audioMetadataLogger,
            final KeyStore senseKeyStore,
            final GroupFlipper groupFlipper,
            final ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.audioBucketName = audioBucketName;
        this.audioFeaturesFirehose = audioFeaturesFirehose;
        this.debug = debug;
        this.audioMetadataLogger = audioMetadataLogger;
        this.keyStore = senseKeyStore;
        this.groupFlipper = groupFlipper;
        this.audioFeaturesFirehoseStreamName = audioFeaturesFirehoseStreamName;
        this.objectMapper = objectMapper;
    }

    @POST
    @Timed
    @Path("/keyword_features")
    public void getKeywordFeatures(byte[] body) {
        final String ipAddress = getIpAddress(request);

        final SignedMessage signedMessage = SignedMessage.parse(body);

        //get Sense (device) ID from the header
        String debugSenseId = this.request.getHeader(HelloHttpHeader.SENSE_ID);
        if (debugSenseId == null) {
            debugSenseId = "";
        }

        LOGGER.debug("sense_id={}", debugSenseId);

        SimpleMatrixProtos.SimpleMatrix message = SimpleMatrixProtos.SimpleMatrix.getDefaultInstance();

        //deserialize protobuf
        try {
            message = SimpleMatrixProtos.SimpleMatrix.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            LOGGER.error("endpoint=keyword-features error=protobuf-parsing-failed sense_id={} ip_address={} message={}", debugSenseId, ipAddress, exception.getMessage());
            throwPlainTextError(Response.Status.BAD_REQUEST, "");
        }

        //verify header sense id matches protobuf
        if (!message.hasDeviceId() || message.getDeviceId().isEmpty()) {
            LOGGER.error("endpoint=keyword-features error=empty-device-id sense_id={}", debugSenseId);
            throwPlainTextError(Response.Status.BAD_REQUEST, "empty device id");
        }

        final String senseId = message.getDeviceId();

        //confirm message sense id matches header
        if (!senseId.equals(debugSenseId)) {
            LOGGER.error("endpoint=keyword-features error=sense-id-no-match sense_id={} proto-sense-id={}", debugSenseId, senseId);
            throwPlainTextError(Response.Status.BAD_REQUEST, "Device ID doesn't match header");
        }

        //count number of bytes across payload
        int num_bytes = 0;
        for (int iPayload = 0; iPayload < message.getPayloadCount(); iPayload++) {
            num_bytes += message.getPayload(iPayload).size();
        }

        LOGGER.info("endpoint=keyword-features sense_id={} protobuf-payload-size={} protobuf-id={}", debugSenseId, num_bytes,message.getId());

        final List<String> groups = groupFlipper.getGroups(senseId);

        //check feature flip
        if(!featureFlipper.deviceFeatureActive(ServiceFeatureFlipper.SERVER_ACCEPTS_KEYWORD_FEATURES.getFeatureName(), senseId, groups)) {
            LOGGER.trace("{} is disabled for {}", ServiceFeatureFlipper.SERVER_ACCEPTS_KEYWORD_FEATURES.getFeatureName(), senseId);
            return;
        }

        final Optional<byte[]> keyBytes = keyStore.get(senseId);

        //verify message is signed
        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(keyBytes.get());

        if(error.isPresent()) {
            LOGGER.error("endpoint=keyword-features error=signature-failed sense_id={} ip_address={} message={}", senseId, ipAddress, error.get().message);
            throwPlainTextError(Response.Status.UNAUTHORIZED, "");
        }

        //insert into kinesis stream
        try {
            final SimpleMatrix simpleMatrix = SimpleMatrix.createFromProtobuf(message, DateTime.now().getMillis());

            final String jsonPayload = objectMapper.writeValueAsString(simpleMatrix);

            final PutRecordRequest request = new PutRecordRequest()
                    .withDeliveryStreamName(audioFeaturesFirehoseStreamName)
                    .withRecord(new Record().
                            withData(ByteBuffer.wrap(jsonPayload.getBytes())));

            audioFeaturesFirehose.putRecordAsync(request, new AsyncHandler<PutRecordRequest, PutRecordResult>() {
                @Override
                public void onError(Exception exception) {
                    LOGGER.error("endpoint=keyword-features error=failed-to-write-to-audioFeaturesFirehose sense_id={} ip_address={} message={}", senseId, ipAddress, exception.getMessage());
                }

                @Override
                public void onSuccess(PutRecordRequest request, PutRecordResult putRecordResult) {
                    //oh goody, I got my data put
                }
            });


        } catch (IOException exception) {
            LOGGER.error("endpoint=keyword-features error=fail-convert-to-json sense_id={} ip_address={} message={}", senseId, ipAddress, exception.getMessage());
        }




    }

    @POST
    @Path("/features")
    public void getAudioFeatures(byte[] body) {


        final SignedMessage signedMessage = SignedMessage.parse(body);
        MatrixProtos.MatrixClientMessage message = MatrixProtos.MatrixClientMessage.getDefaultInstance();

        try {
            message = MatrixProtos.MatrixClientMessage.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throwPlainTextError(Response.Status.BAD_REQUEST, "");
        }

        final String deviceId = message.getDeviceId();
        if(!featureFlipper.deviceFeatureActive(FeatureFlipper.ALWAYS_ON_AUDIO, deviceId, new ArrayList<String>())) {
            LOGGER.trace("{} is disabled for {}", FeatureFlipper.ALWAYS_ON_AUDIO, deviceId);
            return;
        }

        final Optional<byte[]> keyBytes = keyStore.get(deviceId);

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(keyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throwPlainTextError(Response.Status.UNAUTHORIZED, "");
        }

        //DO NOTHING!
        //dataLogger.put(deviceId, signedMessage.body);
    }


    @POST
    @Path("/raw")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    public void getAudio(@Context HttpServletRequest request, byte[] body) {

        final SignedMessage signedMessage = SignedMessage.parse(body);
        FileTransfer.FileMessage message = FileTransfer.FileMessage.getDefaultInstance();
        try {
            message = FileTransfer.FileMessage.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throwPlainTextError(Response.Status.BAD_REQUEST, "");
        }

        if(!featureFlipper.deviceFeatureActive(FeatureFlipper.ALWAYS_ON_AUDIO, message.getDeviceId(), new ArrayList<String>())) {
            LOGGER.trace("{} is disabled for {}", FeatureFlipper.AUDIO_STORAGE, message.getDeviceId());
            return;
        }

        final Optional<byte[]> keyBytes = keyStore.get(message.getDeviceId());
        if(!keyBytes.isPresent()) {
            throwPlainTextError(Response.Status.UNAUTHORIZED, "");
        }
        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(keyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throwPlainTextError(Response.Status.UNAUTHORIZED, "");
        }

        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(message.toByteArray());
        final String objectName = String.format("audio/%s/%s", message.getDeviceId(), message.getUnixTime());

        final ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(message.toByteArray().length);

        s3Client.putObject(audioBucketName, objectName, byteArrayInputStream, metadata);
        try {
            byteArrayInputStream.close();
        } catch (IOException e) {
            LOGGER.error("Failed saving to S3: {}", e.getMessage());
        }

        final EncodeProtos.AudioFileMetadata audioFileMetadata = EncodeProtos.AudioFileMetadata.newBuilder()
                .setDeviceId(message.getDeviceId())
                .setS3Url(objectName)
                .setUnixTime(message.getUnixTime())
                .build();

        audioMetadataLogger.putAsync(message.getDeviceId(), audioFileMetadata.toByteArray());
    }
}
