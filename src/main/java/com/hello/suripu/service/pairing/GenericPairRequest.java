package com.hello.suripu.service.pairing;

import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.util.PairAction;

public class GenericPairRequest {

    private final String senseId;
    private final byte[] encryptedRequest;
    private final KeyStore keyStore;
    private final PairAction pairAction;
    private final String ipAddress;
    private final HardwareVersion senseHardwareVersion;

    private GenericPairRequest(final String senseId, final byte[] encryptedRequest, final KeyStore keyStore, final PairAction pairAction, final String ipAddress, final HardwareVersion senseHardwareVersion) {
        this.senseId = senseId;
        this.encryptedRequest = encryptedRequest;
        this.keyStore = keyStore;
        this.pairAction = pairAction;
        this.ipAddress = ipAddress;
        this.senseHardwareVersion = senseHardwareVersion;
    }

    public static GenericPairRequest create(final String senseId, final byte[] encryptedRequest, final KeyStore keyStore, final PairAction pairAction, final String ipAddress, final HardwareVersion senseHardwareVersion) {
        return new GenericPairRequest(senseId, encryptedRequest, keyStore, pairAction, ipAddress, senseHardwareVersion);
    }

    public String senseId() {
        return senseId;
    }

    public byte[] encryptedRequest() {
        return encryptedRequest;
    }

    public KeyStore keyStore() {
        return keyStore;
    }

    public PairAction pairAction() {
        return pairAction;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public HardwareVersion senseHardwareVersion() {
        return senseHardwareVersion;
    }
}
