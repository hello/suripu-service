package com.hello.suripu.service.pairing.sense;

import com.hello.suripu.core.firmware.HardwareVersion;

public class SensePairingRequest {

    private final Long accountId;
    private final String senseId;
    private final boolean canSwap;
    private final HardwareVersion hardwareVersion;


    private SensePairingRequest(final Long accountId, final String senseId, final boolean canSwap, final HardwareVersion hardwareVersion) {
        this.accountId = accountId;
        this.senseId = senseId;
        this.canSwap = canSwap;
        this.hardwareVersion = hardwareVersion;
    }


    public static SensePairingRequest create(final Long accountId, final String senseId, final HardwareVersion hardwareVersion) {
        return new SensePairingRequest(accountId, senseId, false, hardwareVersion);
    }

    public static SensePairingRequest create(final Long accountId, final String senseId, final boolean canSwap, final HardwareVersion hardwareVersion) {
        return new SensePairingRequest(accountId, senseId, canSwap, hardwareVersion);
    }

    public Long accountId() {
        return accountId;
    }

    public String senseId() {
        return senseId;
    }

    public boolean canSwap() {
        return canSwap;
    }

    public HardwareVersion hardwareVersion() {
        return hardwareVersion;
    }
}
