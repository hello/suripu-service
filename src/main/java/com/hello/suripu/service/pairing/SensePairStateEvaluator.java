package com.hello.suripu.service.pairing;

import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.models.DeviceAccountPair;

import java.util.List;

public class SensePairStateEvaluator {

    private final DeviceDAO deviceDAO;

    public SensePairStateEvaluator(DeviceDAO deviceDAO) {
        this.deviceDAO = deviceDAO;
    }

    public PairState getSensePairingState(final SensePairingRequest request){
        final List<DeviceAccountPair> pairedSense = this.deviceDAO.getSensesForAccountId(request.accountId());
        if(pairedSense.size() > 1){  // This account already paired with multiple senses
            return PairState.PAIRING_VIOLATION;
        }

        if(pairedSense.isEmpty()){
            return PairState.NOT_PAIRED;
        }

        if(pairedSense.get(0).externalDeviceId.equals(request.senseId())){
            return PairState.PAIRED_WITH_CURRENT_ACCOUNT;  // only one sense, and it is current sense, firmware retry request
        }

        return PairState.PAIRING_VIOLATION;  // already paired with another one.
    }
}
