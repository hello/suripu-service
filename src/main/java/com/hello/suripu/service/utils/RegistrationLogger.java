package com.hello.suripu.service.utils;

import com.google.common.base.Optional;

public interface RegistrationLogger {
    void setSenseId(String senseId);

    void setAccountId(Long accountId);

    void logFailure(Optional<String> pillId,
                    String info);

    void logProgress(Optional<String> pillId,
                     String info);

    void logSuccess(Optional<String> pillId,
                    String info);

    boolean commit();
}
