package com.hello.suripu.service.pairing.sense;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.swap.Intent;
import com.hello.suripu.core.swap.Result;
import com.hello.suripu.core.swap.Swapper;
import com.hello.suripu.service.pairing.PairState;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SensePairStateEvaluatorTest {

    private SensePairStateEvaluator evaluator;
    private DeviceDAO deviceDAO;
    private Swapper swapper;

    @Before
    public void setUp() {
        this.deviceDAO = mock(DeviceDAO.class);
        this.swapper = mock(Swapper.class);
        this.evaluator = new SensePairStateEvaluator(deviceDAO, swapper);
    }

    @Test
    public void testNotPaired() {
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final ImmutableList<DeviceAccountPair> empty = ImmutableList.copyOf(new ArrayList<DeviceAccountPair>());
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(empty);
        Assert.assertEquals(PairState.NOT_PAIRED, evaluator.getSensePairingState(request));
    }

    @Test
    public void testPairedSameAccount() {
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                request.senseId(),
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRED_WITH_CURRENT_ACCOUNT, evaluator.getSensePairingState(request));
    }

    @Test
    public void testUserIsPairedToDifferentSense() {
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRING_VIOLATION, evaluator.getSensePairingState(request));
    }

    @Test
    public void testUserIsPairedToMulitpleSense() {
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair, pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRING_VIOLATION, evaluator.getSensePairingState(request));
    }

    @Test
    public void testUserHasNoSwap() {

        final SensePairingRequest request = SensePairingRequest.create(99L, "abc");
        when(swapper.query(request.senseId())).thenReturn(Optional.<Intent>absent());
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair, pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        assertEquals(PairState.PAIRING_VIOLATION, evaluator.getSensePairingState(request));
    }

    @Test
    public void testUserHasSwap() {

        // TODO: this is a useless test for now
        final SensePairingRequest request = SensePairingRequest.create(99L, "abc", true);
        final Intent intent = Intent.create(request.senseId(), "old", request.accountId());
        when(swapper.query(request.senseId())).thenReturn(Optional.of(intent));
        when(swapper.swap(intent)).thenReturn(Result.success());
        final DeviceAccountPair pair = new DeviceAccountPair(
                request.accountId(),
                0L,
                "different_sense",
                DateTime.now(DateTimeZone.UTC)
        );
        final ImmutableList<DeviceAccountPair> senses = ImmutableList.copyOf(Lists.newArrayList(pair, pair));
        when(deviceDAO.getSensesForAccountId(request.accountId())).thenReturn(senses);
        when(deviceDAO.registerSense(request.accountId(), request.senseId())).thenReturn(1L);
        assertEquals(PairState.PAIRED_WITH_CURRENT_ACCOUNT, evaluator.getSensePairingStateAndMaybeSwap(request));
    }
}
