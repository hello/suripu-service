package com.hello.suripu.service.pairing.pill;

import com.hello.suripu.service.pairing.PairingAttempt;

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

    public static PillPairingRequest from(final PairingAttempt attempt) {
        return new PillPairingRequest(attempt.senseId(), attempt.pillId().orNull(), attempt.accountId(), attempt.isDebugMode());
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
