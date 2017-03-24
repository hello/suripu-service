package com.hello.suripu.service.resources;


import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.librato.rollout.RolloutClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collections;

public abstract class BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseResource.class);

    @Inject
    RolloutClient featureFlipper;


    protected BaseResource()  {
        ObjectGraphRoot.getInstance().inject(this);
    }

    /**
     * Use this method to return plain text errors (to Sense)
     * It returns byte[] just to match the signature of most methods interacting with Sense
     * @param status
     * @param message
     * @return
     */
    protected byte[] plainTextError(final Response.Status status, final String message) {
        LOGGER.error("{} : {} ", status, (message.isEmpty()) ? "-" : message);
        throw new WebApplicationException(Response.status(status)
                .entity(message)
                .type(MediaType.TEXT_PLAIN_TYPE).build()
        );
    }

    public void throwPlainTextError(final Response.Status status, final String message) throws WebApplicationException {
        plainTextError(status, message);
    }

    // TODO: add similar method for JSON Error

    /**
     * Returns the first IP address specified in headers or empty string
     * @param request
     * @return
     */
    public static String getIpAddress(final HttpServletRequest request) {
        final String ipAddress = (request.getHeader("X-Forwarded-For") == null) ? request.getRemoteAddr() : request.getHeader("X-Forwarded-For");
        if (ipAddress == null) {
            return "";
        }

        final String[] ipAddresses = ipAddress.split(",");
        return ipAddresses[0]; // always return first one?
    }

    // Calibration is enabled on a per device basis
    protected Boolean hasCalibrationEnabled(final String senseId) {
        return featureFlipper.deviceFeatureActive(FeatureFlipper.CALIBRATION, senseId, Collections.EMPTY_LIST);
    }
}
