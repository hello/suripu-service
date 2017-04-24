package com.hello.suripu.service.pairing.pill;

import com.google.common.collect.Lists;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.service.pairing.PairState;
import com.hello.suripu.service.utils.RegistrationLogger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class PillPairStateEvaluatorTest {

    final Long accountId = 888L;
    final Long otherAccountId = 444L;

    @Test
    public void testUserHasNoPillPaired() {
        final RegistrationLogger logger = mock(RegistrationLogger.class);

        final PillPairingRequest request = PillPairingRequest.create("sense", "pill", accountId, false);

        final List<DeviceAccountPair> empty = new ArrayList<>();
        final PairState state = PillPairStateEvaluator.get(request, empty, empty, logger);
        assertEquals(PairState.NOT_PAIRED, state);
    }

    @Test
    public void testUserHasAlreadyOnePillPaired() {
        final RegistrationLogger logger = mock(RegistrationLogger.class);

        final PillPairingRequest request = PillPairingRequest.create("sense", "pill", accountId, false);

        final List<DeviceAccountPair> empty = new ArrayList<>();
        final List<DeviceAccountPair> pills = Lists.newArrayList(
                new DeviceAccountPair(request.accountId(), 0L, request.pillId(), DateTime.now(DateTimeZone.UTC))
        );
        final PairState state = PillPairStateEvaluator.get(request, pills, empty, logger);
        assertEquals(PairState.PAIRING_VIOLATION, state);
    }

    @Test
    public void testUserHasNoPillPairedButPillPairedToOtherAccount() {
        final RegistrationLogger logger = mock(RegistrationLogger.class);

        final PillPairingRequest request = PillPairingRequest.create("sense", "pill", accountId, false);

        final List<DeviceAccountPair> empty = new ArrayList<>();
        final List<DeviceAccountPair> pills = Lists.newArrayList(
                new DeviceAccountPair(otherAccountId, 0L, request.pillId(), DateTime.now(DateTimeZone.UTC))
        );
        final PairState state = PillPairStateEvaluator.get(request, empty, pills, logger);
        assertEquals(PairState.PAIRED_WITH_OTHER_ACCOUNT, state);
    }

    @Test
    public void testUserPillAndPillAlsoPairedToOtherAccount() {
        final RegistrationLogger logger = mock(RegistrationLogger.class);

        final PillPairingRequest request = PillPairingRequest.create("sense", "pill", accountId, false);


        final List<DeviceAccountPair> mine = Lists.newArrayList(
                new DeviceAccountPair(accountId, 0L, request.pillId(), DateTime.now(DateTimeZone.UTC))
        );
        final List<DeviceAccountPair> pills = Lists.newArrayList(
                new DeviceAccountPair(otherAccountId, 0L, request.pillId(), DateTime.now(DateTimeZone.UTC))
        );
        final PairState state = PillPairStateEvaluator.get(request, mine, pills, logger);
        assertEquals(PairState.PAIRED_WITH_CURRENT_ACCOUNT, state);
    }

    @Test
    public void testPillAlreadyPairedDebugMode() {
        final RegistrationLogger logger = mock(RegistrationLogger.class);

        final PillPairingRequest request = PillPairingRequest.create("sense", "pill", accountId, true);

        final List<DeviceAccountPair> empty = new ArrayList<>();
        final List<DeviceAccountPair> pills = Lists.newArrayList(
                new DeviceAccountPair(otherAccountId, 0L, request.pillId(), DateTime.now(DateTimeZone.UTC))
        );
        final PairState state = PillPairStateEvaluator.get(request, empty, pills, logger);
        assertEquals(PairState.NOT_PAIRED, state);
    }
}
