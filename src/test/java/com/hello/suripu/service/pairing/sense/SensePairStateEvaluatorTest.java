package com.hello.suripu.service.pairing.sense;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.service.pairing.PairState;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SensePairStateEvaluatorTest {

    @Test
    public void testNotPaired() {
        final DeviceDAO deviceDAO = mock(DeviceDAO.class);
        final SensePairStateEvaluator sensePairStateEvaluator = new SensePairStateEvaluator(deviceDAO);
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final ImmutableList<DeviceAccountPair> empty = ImmutableList.copyOf(new ArrayList<DeviceAccountPair>());
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(empty);
        Assert.assertEquals(PairState.NOT_PAIRED, sensePairStateEvaluator.getSensePairingState(request));
    }

    @Test
    public void testPairedSameAccount() {
        final DeviceDAO deviceDAO = mock(DeviceDAO.class);
        final SensePairStateEvaluator sensePairStateEvaluator = new SensePairStateEvaluator(deviceDAO);
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                request.senseId(),
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRED_WITH_CURRENT_ACCOUNT, sensePairStateEvaluator.getSensePairingState(request));
    }

    @Test
    public void testUserIsPairedToDifferentSense() {
        final DeviceDAO deviceDAO = mock(DeviceDAO.class);
        final SensePairStateEvaluator sensePairStateEvaluator = new SensePairStateEvaluator(deviceDAO);
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRING_VIOLATION, sensePairStateEvaluator.getSensePairingState(request));
    }

    @Test
    public void testUserIsPairedToMulitpleSense() {
        final DeviceDAO deviceDAO = mock(DeviceDAO.class);
        final SensePairStateEvaluator sensePairStateEvaluator = new SensePairStateEvaluator(deviceDAO);
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair, pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRING_VIOLATION, sensePairStateEvaluator.getSensePairingState(request));
    }
}
