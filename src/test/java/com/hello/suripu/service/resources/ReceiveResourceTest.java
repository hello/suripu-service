package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import com.hello.suripu.core.models.AlarmExpansion;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.ValueRange;
import com.hello.suripu.service.configuration.SenseUploadConfiguration;

import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Created by pangwu on 1/23/15.
 */
public class ReceiveResourceTest {

    @Test
    public void testComputeNextUploadInterval(){
        final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
        final long actualRingTime = DateTime.now().plusMinutes(3).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

        final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
        final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(), senseUploadConfiguration, false);
        assertThat(uploadCycle, is(SenseUploadConfiguration.DEFAULT_UPLOAD_INTERVAL));

    }

    @Test
    public void testShouldWriteRingHistory(){
        final DateTime now = DateTime.now();
        final DateTime actualRingTime = now.plusMinutes(2);
        final DateTime expectedRingTime = now.plusMinutes(3);
        final RingTime ringTime = new RingTime(actualRingTime.getMillis(), expectedRingTime.getMillis(), new long[0], true, Lists.newArrayList());

        assertThat(ReceiveResource.shouldWriteRingTimeHistory(now, ringTime, 3), is(true));
        assertThat(ReceiveResource.shouldWriteRingTimeHistory(now, ringTime, 1), is(false));
        assertThat(ReceiveResource.shouldWriteRingTimeHistory(now.plusMinutes(5), ringTime, 1), is(false));
    }


    @Test
    public void testComputeNextUploadInterval1HourInFuture(){


        for(int i = 1; i < DateTimeConstants.MINUTES_PER_DAY; i++) {
            final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
            final long actualRingTime = DateTime.now().plusMinutes(i).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

            final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
            final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(), senseUploadConfiguration, false);
            assertThat(uploadCycle <= 4, is(true));
        }
    }

    @Test
    public void testComputeNextUploadIntervalAlarmInThePast(){


        for(int i = 1; i < DateTimeConstants.MINUTES_PER_DAY; i++) {
            final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
            final long actualRingTime = DateTime.now().minusMinutes(i).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

            final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
            final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(), senseUploadConfiguration, false);
            // Next interval should never be > the maximum interval (increased non peak)
            assertThat(uploadCycle <= senseUploadConfiguration.getIncreasedNonPeakUploadInterval(), is(true));
        }
    }

    @Test
    public void testComputeNextUploadIntervalShouldBePositive(){

        for(int i = 1; i < DateTimeConstants.MINUTES_PER_DAY; i++) {
            final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
            final long actualRingTime = DateTime.now().minusMinutes(i).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

            final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
            final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(), senseUploadConfiguration, false);
            assertThat(uploadCycle > 0, is(true));
        }
    }

    @Test
    public void testComputeNextUploadIntervalRandomNow(){
        final Random random = new Random(DateTime.now().getMillis());

        for(int i = 1; i < DateTimeConstants.MINUTES_PER_DAY; i++) {
            final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
            final long actualRingTime = DateTime.now().plusMinutes(i).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

            final DateTime current = new DateTime(random.nextLong());
            final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
            final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, current, senseUploadConfiguration, false);
            assertThat(uploadCycle <= senseUploadConfiguration.getIncreasedNonPeakUploadInterval(), is(true));
        }
    }

    @Test
    public void testComputePassRingTimeUploadIntervalRandomNow(){

        final Random random = new Random(DateTime.now().getMillis());

        for(int i = 1; i < DateTimeConstants.MINUTES_PER_DAY; i++) {
            final long actualRingTime = DateTime.now().plusMinutes(i).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

            final DateTime current = new DateTime(random.nextLong());
            final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
            final int uploadCycle = ReceiveResource.computePassRingTimeUploadInterval(nextRingTime, current, 10);
            assertThat(uploadCycle <= 10, is(true));
        }
    }

    @Test
    public void testComputeNextUploadIntervalReduced(){

        final SenseUploadConfiguration senseUploadConfiguration = new SenseUploadConfiguration();
        final long actualRingTime = DateTime.now(DateTimeZone.UTC).withDayOfWeek(3).withHourOfDay(12).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();

        final RingTime nextRingTime = new RingTime(actualRingTime, actualRingTime, new long[0], false, Lists.newArrayList());
        final int increasedUploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(DateTimeZone.UTC).withDayOfWeek(3).withHourOfDay(13).withMinuteOfHour(0), senseUploadConfiguration, true);
        final int uploadCycle = ReceiveResource.computeNextUploadInterval(nextRingTime, DateTime.now(DateTimeZone.UTC).withDayOfWeek(3).withHourOfDay(13).withMinuteOfHour(0), senseUploadConfiguration, false);

        final Integer increasedInterval = SenseUploadConfiguration.INCREASED_INTERVAL_NON_PEAK;
        assertThat(increasedInterval.equals(increasedInterval), is(true));
        assertThat(increasedInterval > uploadCycle, is(true));
    }

    @Test
    public void testFWBugClockSkewNotCorrected() {
        DateTime sampleDateTime = DateTime.now().plusHours(10);
        final Optional<Long> notCorrectedFutureDT = ReceiveResource.correctForPillClockSkewBug(sampleDateTime, DateTime.now());
        Long timestamp = 0L;
        if (notCorrectedFutureDT.isPresent()) {
            timestamp = notCorrectedFutureDT.get();
        }
        assertThat(timestamp, is(0L));

        sampleDateTime = DateTime.now().minusHours(10);
        final Optional<Long> notCorrectedPastDT = ReceiveResource.correctForPillClockSkewBug(sampleDateTime, DateTime.now());
        if (notCorrectedPastDT.isPresent()) {
            timestamp = notCorrectedFutureDT.get();
        }
        assertThat(timestamp, is(0L));
    }

//    @Test
//    public void testFWClockSkewBugCorrected(){
//        final DateTime now = DateTime.now();
//        DateTime sampleDateTime = now.plusMonths(6).plusSeconds(1);
//        final Optional<Long> correctedDT = ReceiveResource.correctForPillClockSkewBug(sampleDateTime, now);
//        Long timestamp = 0L;
//        if (correctedDT.isPresent()) {
//            timestamp = correctedDT.get();
//        }
//        final DateTime correctDT = now.plusSeconds(1);
//        assertThat(timestamp, is(correctDT.getMillis()/1000L));
//    }

//    @Test
//    public void testFWClockSkewBugWithFixedDate() {
//        final DateTime now = DateTimeUtil.datetimeStringToDateTime("2015-10-07 17:56:53");
//        final DateTime sampleDateTime = DateTimeUtil.datetimeStringToDateTime("2016-04-07 17:56:49");
//        final Optional<Long> correctedDT = ReceiveResource.correctForPillClockSkewBug(sampleDateTime, now);
//        Long timestamp = 0L;
//        if (correctedDT.isPresent()) {
//            timestamp = correctedDT.get();
//        }
//        final Long expectedTimestamp = sampleDateTime.minusMonths(DateTimeUtil.MONTH_OFFSET_FOR_CLOCK_BUG).getMillis()/1000L;
//        assertThat(timestamp, is(expectedTimestamp));
//    }

    @Test
    public void testShouldLogAlarmActions() {
        final Integer ALARM_ACTIONS_WINDOW_MINS = 60;
        final List<AlarmExpansion> expansions = Lists.newArrayList();
        expansions.add(new AlarmExpansion(1, true, "fake", "Hue", ValueRange.createEmpty()));

        final long ringTimeTooFarInFuture = DateTime.now(DateTimeZone.UTC).plusMinutes(62).getMillis();
        final RingTime nextRingTimeTooFarInFuture = new RingTime(ringTimeTooFarInFuture, ringTimeTooFarInFuture, new long[0], false, Lists.newArrayList());

        final long ringTimeInWindow = DateTime.now(DateTimeZone.UTC).plusMinutes(59).getMillis();
        final RingTime nextRingTimeInWindow = new RingTime(ringTimeInWindow, ringTimeInWindow, new long[0], false, expansions);

        final long ringTimeOld = DateTime.now(DateTimeZone.UTC).minusMinutes(2).getMillis();
        final RingTime nextRingTimeOld = new RingTime(ringTimeOld, ringTimeOld, new long[0], false, Lists.newArrayList());

        final long ringTimeNoExpected = DateTime.now(DateTimeZone.UTC).plusMinutes(59).getMillis();
        final RingTime nextRingTimeNoExpected = new RingTime(ringTimeNoExpected, ringTimeNoExpected, new long[0], false, expansions);

        final long ringTimeInWindowNoExpansions = DateTime.now(DateTimeZone.UTC).plusMinutes(59).getMillis();
        final RingTime nextRingTimeInWindowNoExpansions = new RingTime(ringTimeInWindowNoExpansions, ringTimeInWindowNoExpansions, new long[0], false, Lists.newArrayList());

        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), nextRingTimeTooFarInFuture, ALARM_ACTIONS_WINDOW_MINS), is(false));
        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), nextRingTimeInWindow, ALARM_ACTIONS_WINDOW_MINS), is(true));
        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), nextRingTimeOld, ALARM_ACTIONS_WINDOW_MINS), is(false));
        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), nextRingTimeNoExpected, ALARM_ACTIONS_WINDOW_MINS), is(true));
        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), nextRingTimeInWindowNoExpansions, ALARM_ACTIONS_WINDOW_MINS), is(false));
        assertThat(ReceiveResource.shouldLogAlarmActions(DateTime.now(DateTimeZone.UTC), RingTime.createEmpty(), ALARM_ACTIONS_WINDOW_MINS), is(false));
    }

    @Test
    public void testSenseOneFiveDownloadStatus() {
        boolean disabled = ReceiveResource.downloadDisabledForOneFive(false, false); // not DVT, not in white-list
        assertThat(disabled, is(true));

        disabled = ReceiveResource.downloadDisabledForOneFive(true, false); // is DVT, not in white-list
        assertThat(disabled, is(false));

        disabled = ReceiveResource.downloadDisabledForOneFive(true, true); // is DVT, also in white-list
        assertThat(disabled, is(false));

        disabled = ReceiveResource.downloadDisabledForOneFive(false, true); // not DVT but in white-list
        assertThat(disabled, is(false));
    }
}
