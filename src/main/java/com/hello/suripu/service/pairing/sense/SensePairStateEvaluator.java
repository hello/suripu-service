package com.hello.suripu.service.pairing.sense;

import com.google.common.base.Optional;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.swap.Intent;
import com.hello.suripu.core.swap.Result;
import com.hello.suripu.core.swap.Swapper;
import com.hello.suripu.service.pairing.PairState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SensePairStateEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SensePairStateEvaluator.class);

    private final DeviceDAO deviceDAO;
    private final Swapper swapper;

    public SensePairStateEvaluator(final DeviceDAO deviceDAO, final Swapper swapper) {
        this.deviceDAO = deviceDAO;
        this.swapper = swapper;
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

    public PairState getSensePairingStateAndMaybeSwap(final SensePairingRequest request) {
        if(!request.canSwap()) {
            return getSensePairingState(request);
        }

        LOGGER.info("action=sense-swap sense_id={} account_id={}", request.senseId(), request.accountId());
        final Optional<Intent> swapIntent = swapper.query(request.senseId());
        if(!swapIntent.isPresent()) {
            return getSensePairingState(request);
        }
        final Intent intent = swapIntent.get();
        if(!intent.accountId().equals(request.accountId())) {
            LOGGER.error("action=sense-swap error=account-id-mismatch intent_account_id={} pair_account_id={}",
                    intent.accountId(), request.accountId()
            );
        }
        final Result swapResult = swapper.swap(intent);
        if(swapResult.successful()) {
            return PairState.PAIRED_WITH_CURRENT_ACCOUNT;
        }

        return PairState.PAIRING_VIOLATION;
    }
}
