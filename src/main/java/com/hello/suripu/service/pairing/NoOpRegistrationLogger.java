package com.hello.suripu.service.pairing;

import com.google.common.base.Optional;
import com.hello.suripu.service.utils.RegistrationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoOpRegistrationLogger implements RegistrationLogger {

    private final static String DEFAULT_PILL_ID_NAME = "unknown";

    private final static Logger LOGGER = LoggerFactory.getLogger(NoOpRegistrationLogger.class);

    @Override
    public void setSenseId(String senseId) {
        LOGGER.info("action=set-sense-id sense_id={}", senseId);
    }

    @Override
    public void setAccountId(Long accountId) {
        LOGGER.info("action=set-account-id account_id={}", accountId);
    }

    @Override
    public void logFailure(Optional<String> pillId, String info) {
        LOGGER.warn("action=log-failure info={} pill_id={}", info, pillId.or(DEFAULT_PILL_ID_NAME));
    }

    @Override
    public void logProgress(Optional<String> pillId, String info) {
        LOGGER.info("action=log-progress info={} pill_id={}", info, pillId.or(DEFAULT_PILL_ID_NAME));
    }

    @Override
    public void logSuccess(Optional<String> pillId, String info) {
        LOGGER.info("action=log-success info={} pill_id={}", info, pillId.or(DEFAULT_PILL_ID_NAME));
    }

    @Override
    public boolean commit() {
        return false;
    }
}
