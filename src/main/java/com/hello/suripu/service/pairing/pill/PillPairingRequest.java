package com.hello.suripu.service.pairing.pill;

public class PillPairingRequest {

    private final String senseId;
    private final String pillId;
    private final Long accountId;
    private final boolean debugMode;

    private PillPairingRequest(final String senseId, final String pillId, final Long accountId, final boolean debugMode) {
        this.senseId = senseId;
        this.pillId = pillId;
        this.accountId = accountId;
        this.debugMode = debugMode;
    }

    public static PillPairingRequest create(final String senseId, final String pillId, final Long accountId, final boolean debugMode) {
        return new PillPairingRequest(senseId, pillId, accountId, debugMode);
    }

    public String senseId() {
        return senseId;
    }

    public String pillId(){
        return pillId;
    }

    public Long accountId() {
        return accountId;
    }

    public boolean debugMode() {
        return debugMode;
    }

}
