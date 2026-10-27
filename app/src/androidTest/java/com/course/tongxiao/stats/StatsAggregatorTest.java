package com.course.tongxiao.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.ui.stats.StatsAggregator;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step 6.3 G2: the three statistics are pure functions, so they are pinned here
 * before any pixel is drawn.
 *
 * <p>Assertions are on the exact numbers the screen renders: the earliest stop of
 * a day, the descending snooze order, and which STOPPED rows count as "muted by a
 * roommate". Empty input is first-class because a fresh install has no history —
 * crashing there would break the stats tab for every new user.
 */
@RunWith(AndroidJUnit4.class)
public class StatsAggregatorTest {

    /** Fixed "today" so the 14-day window boundaries are deterministic. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private static long millis(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, LocalTime.of(hour, minute))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static AlarmEvent event(AlarmEvent.Type type, int alarmId,
                                    LocalDate day, int hour, int minute, String meta) {
        AlarmEvent event = new AlarmEvent();
        event.type = type;
        event.alarmId = alarmId;
        event.fireAt = millis(day, hour, minute) - 60_000L;
        event.stopAt = millis(day, hour, minute);
        event.meta = meta;
        return event;
    }

    private static AlarmEvent stopped(LocalDate day, int hour, int minute) {
        return event(AlarmEvent.Type.STOPPED, 1, day, hour, minute, "{\"source\":\"user\"}");
    }

    @Test
    public void emptyOrNullEvents_produceEmptyStatsWithoutCrashing() {
        StatsAggregator.Stats fromNull = StatsAggregator.aggregate(null, TODAY);
        StatsAggregator.Stats fromEmpty = StatsAggregator.aggregate(Collections.emptyList(), TODAY);

        for (StatsAggregator.Stats stats : new StatsAggregator.Stats[]{fromNull, fromEmpty}) {
            assertTrue("no trend points", stats.trend.isEmpty());
            assertTrue("no snooze rows", stats.snoozeTop.isEmpty());
            assertEquals("no remote mutes", 0, stats.remoteMutedCount);
        }
    }

    @Test
    public void trend_keepsEarliestStopOfEachDay_andSkipsDaysWithoutData() {
        List<AlarmEvent> events = new ArrayList<>();
        // Same day, two stops (the second is a snooze cycle): the FIRST one is the
        // moment the user got up.
        events.add(stopped(TODAY.minusDays(1), 7, 20));
        events.add(stopped(TODAY.minusDays(1), 7, 50));
        // Today: earlier wake-up.
        events.add(stopped(TODAY, 6, 55));
        // Not STOPPED: only a stop can place a point on the chart.
        events.add(event(AlarmEvent.Type.FIRED, 1, TODAY.minusDays(2), 6, 30, null));
        events.add(event(AlarmEvent.Type.SNOOZE, 1, TODAY.minusDays(1), 7, 30, null));
        events.add(event(AlarmEvent.Type.SKIPPED_HOLIDAY, 1, TODAY.minusDays(3), 6, 30, null));

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals("one point per day with a stop", 2, stats.trend.size());
        assertEquals(TODAY.minusDays(1), stats.trend.get(0).date);
        assertEquals("07:20 = 440 minutes", 7 * 60 + 20, stats.trend.get(0).minuteOfDay);
        assertEquals(TODAY, stats.trend.get(1).date);
        assertEquals("06:55 = 415 minutes", 6 * 60 + 55, stats.trend.get(1).minuteOfDay);
    }

    @Test
    public void trend_clipsToTheFourteenDayWindow() {
        List<AlarmEvent> events = new ArrayList<>();
        events.add(stopped(TODAY.minusDays(20), 8, 0)); // too old for the chart
        events.add(stopped(TODAY.minusDays(14), 8, 0)); // one day left of the window
        events.add(stopped(TODAY.minusDays(13), 7, 0)); // first day inside
        events.add(stopped(TODAY.plusDays(1), 7, 0));   // future row: not plotted

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals("only the in-window days survive", 1, stats.trend.size());
        assertEquals(TODAY.minusDays(13), stats.trend.get(0).date);
    }

    @Test
    public void trend_ignoresStoppedRowsWithoutAStopTimestamp() {
        AlarmEvent broken = stopped(TODAY, 7, 0);
        broken.stopAt = 0; // stop moment never recorded: cannot place a point

        StatsAggregator.Stats stats =
                StatsAggregator.aggregate(Collections.singletonList(broken), TODAY);

        assertTrue(stats.trend.isEmpty());
    }

    @Test
    public void snoozeRanking_ordersDescending_andKeepsOnlyTopThree() {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            events.add(event(AlarmEvent.Type.SNOOZE, 11, TODAY, 7, 0, null));
        }
        for (int i = 0; i < 5; i++) {
            events.add(event(AlarmEvent.Type.SNOOZE, 22, TODAY, 7, 0, null));
        }
        for (int i = 0; i < 3; i++) {
            events.add(event(AlarmEvent.Type.SNOOZE, 33, TODAY, 7, 0, null));
        }
        events.add(event(AlarmEvent.Type.SNOOZE, 44, TODAY, 7, 0, null));

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals("Top3 cap", StatsAggregator.SNOOZE_TOP_N, stats.snoozeTop.size());
        assertEquals(22, stats.snoozeTop.get(0).alarmId);
        assertEquals(5, stats.snoozeTop.get(0).count);
        assertEquals(33, stats.snoozeTop.get(1).alarmId);
        assertEquals(3, stats.snoozeTop.get(1).count);
        assertEquals(11, stats.snoozeTop.get(2).alarmId);
        assertEquals(2, stats.snoozeTop.get(2).count);
    }

    @Test
    public void snoozeRanking_breaksTiesByAlarmIdForAStableOrder() {
        List<AlarmEvent> events = new ArrayList<>();
        events.add(event(AlarmEvent.Type.SNOOZE, 7, TODAY, 7, 0, null));
        events.add(event(AlarmEvent.Type.SNOOZE, 3, TODAY, 7, 0, null));

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals("lower alarmId first on equal counts", 3, stats.snoozeTop.get(0).alarmId);
        assertEquals(7, stats.snoozeTop.get(1).alarmId);
    }

    @Test
    public void remoteMuted_countsOnlyStoppedRowsCarryingTheRemoteMarker() {
        List<AlarmEvent> events = new ArrayList<>();
        // Roommate mute: meta source carries "remote".
        events.add(event(AlarmEvent.Type.STOPPED, 1, TODAY, 7, 0,
                "{\"source\":\"remote:device-9\"}"));
        events.add(event(AlarmEvent.Type.STOPPED, 1, TODAY, 7, 10, "{\"source\":\"remote\"}"));
        // Case-insensitive: the marker is a protocol token, not a display string.
        events.add(event(AlarmEvent.Type.STOPPED, 1, TODAY, 7, 20,
                "{\"source\":\"Remote:device-2\"}"));
        // Local stop: must NOT be counted.
        events.add(stopped(TODAY, 7, 30));
        // SNOOZE with the same meta is not a mute.
        events.add(event(AlarmEvent.Type.SNOOZE, 1, TODAY, 7, 40, "{\"source\":\"remote:x\"}"));
        // Missing meta must not crash the counter.
        events.add(event(AlarmEvent.Type.STOPPED, 1, TODAY, 7, 50, null));

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals("three remote stop rows", 3, stats.remoteMutedCount);
        assertTrue(StatsAggregator.isRemoteMute("{\"source\":\"remote:abc\"}"));
        assertFalse(StatsAggregator.isRemoteMute(null));
        assertFalse(StatsAggregator.isRemoteMute("{\"source\":\"user\"}"));
    }

    @Test
    public void aggregate_toleratesNullElementsInTheEventList() {
        List<AlarmEvent> events = new ArrayList<>();
        events.add(null);
        events.add(stopped(TODAY, 6, 40));

        StatsAggregator.Stats stats = StatsAggregator.aggregate(events, TODAY);

        assertEquals(1, stats.trend.size());
        assertEquals(6 * 60 + 40, stats.trend.get(0).minuteOfDay);
    }
}
