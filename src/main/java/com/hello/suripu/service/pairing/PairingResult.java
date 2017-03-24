package com.hello.suripu.service.pairing;

import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.service.utils.RegistrationLogger;

public class PairingResult {

    public final SenseCommandProtos.MorpheusCommand.Builder builder;
    public final RegistrationLogger onboardingLogger;

    public PairingResult(final SenseCommandProtos.MorpheusCommand.Builder builder, final RegistrationLogger onboardingLogger) {
        this.builder = builder;
        this.onboardingLogger = onboardingLogger;
    }
}
