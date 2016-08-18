package com.hello.suripu.service.pairing;

public class SensePairingRequest {

    private final Long accountId;
    private final String senseId;


    private SensePairingRequest(Long accountId, String senseId) {
        this.accountId = accountId;
        this.senseId = senseId;
    }

    public static SensePairingRequest create(Long accountId, String senseId) {
        return new SensePairingRequest(accountId, senseId);
    }

    public Long accountId() {
        return accountId;
    }

    public String senseId() {
        return senseId;
    }
}
