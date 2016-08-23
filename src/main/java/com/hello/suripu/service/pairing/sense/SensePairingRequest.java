package com.hello.suripu.service.pairing.sense;

public class SensePairingRequest {

    private final Long accountId;
    private final String senseId;
    private final boolean canSwap;


    private SensePairingRequest(final Long accountId, final String senseId, final boolean canSwap) {
        this.accountId = accountId;
        this.senseId = senseId;
        this.canSwap = canSwap;
    }


    public static SensePairingRequest create(final Long accountId, final String senseId) {
        return new SensePairingRequest(accountId, senseId, false);
    }

    public static SensePairingRequest create(final Long accountId, final String senseId, final boolean canSwap) {
        return new SensePairingRequest(accountId, senseId, canSwap);
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
}
