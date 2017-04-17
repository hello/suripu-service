package com.hello.suripu.service.pairing;

import com.google.common.base.Optional;
import com.hello.suripu.api.ble.SenseCommandProtos;

public class PairingAttempt {

    final SenseCommandProtos.MorpheusCommand.Builder builder;
    final String senseId;
    final Long accountId;
    final Optional<String> pillId;
    final boolean debugMode;
    final boolean notifyOnConflict;
    final String ipAddress;


    private PairingAttempt(final SenseCommandProtos.MorpheusCommand.Builder builder, final String senseId,
                           final Long accountId, final String pillId, final boolean debugMode, final String ipAddress,
                           final boolean notifyOnConflict) {
        this.builder = builder;
        this.senseId = senseId;
        this.accountId = accountId;
        this.pillId = Optional.fromNullable(pillId);
        this.debugMode = debugMode;
        this.notifyOnConflict = notifyOnConflict;
        this.ipAddress = ipAddress;
    }

    public static PairingAttempt pill(final SenseCommandProtos.MorpheusCommand.Builder builder, final String senseId,
                                      final Long accountId, final String pillId, final boolean debugMode, final String ipAddress,
                                      final boolean notifyOnConflict) {
        return new PairingAttempt(builder,senseId,accountId,pillId,debugMode,ipAddress, notifyOnConflict);
    }

    public static PairingAttempt sense(final SenseCommandProtos.MorpheusCommand.Builder builder, final String senseId,
                                      final Long accountId, final boolean debugMode, final String ipAddress) {
        return new PairingAttempt(builder,senseId,accountId,null,debugMode,ipAddress, false);
    }

    public String senseId() {
        return senseId;
    }

    public Long accountId() {
        return accountId;
    }

    public Optional<String> pillId() {
        return pillId;
    }

    public boolean isDebugMode() {
        return debugMode;
    }
}
