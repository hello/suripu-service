package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.hello.dropwizard.mikkusu.helpers.AdditionalMediaTypes;
import com.hello.suripu.api.logging.LogProtos;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.service.SignedMessage;
import com.yammer.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

@Path("/logs")
public class LogsResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsResource.class);
    private final KeyStore senseKeyStore;
    private final DataLogger dataLogger;
    private final Boolean isProd;

    public LogsResource(final Boolean isProd, final KeyStore senseKeyStore, final DataLogger dataLogger) {
        this.isProd = isProd;
        this.senseKeyStore = senseKeyStore;
        this.dataLogger = dataLogger;

    }

    @Timed
    @POST
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    public void saveLogs(byte[] body) {

        final SignedMessage signedMessage = SignedMessage.parse(body);
        LogProtos.sense_log log;

        try {
            log = LogProtos.sense_log.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        // get MAC address of morpheus

        if(!log.hasDeviceId()){
            LOGGER.error("Cannot get morpheus id");
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        final Optional<byte[]> keyBytes = senseKeyStore.get(log.getDeviceId());
        if(!keyBytes.isPresent()) {
            LOGGER.warn("No AES key found for device = {}", log.getDeviceId());
            return;
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(keyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        final LoggingProtos.LogMessage logMessage = LoggingProtos.LogMessage.newBuilder()
                .setMessage(log.getText())
                .setOrigin("sense")
                .setTs(log.getUnixTime())
                .setDeviceId(log.getDeviceId())
                .setProduction(isProd)
                .build();

        final LoggingProtos.BatchLogMessage batch = LoggingProtos.BatchLogMessage.newBuilder().addMessages(logMessage).build();
        dataLogger.put(log.getDeviceId(), batch.toByteArray());
    }
}
