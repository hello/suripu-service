package com.hello.suripu.service.pairing;

import com.hello.suripu.service.utils.RegistrationLogger;

public interface PairingManager {

    PairingManager withLogger(RegistrationLogger logger);
    PairingResult pairSense(PairingAttempt attempt);
    PairingResult pairPill(PairingAttempt attempt);

    RegistrationLogger logger();
}
