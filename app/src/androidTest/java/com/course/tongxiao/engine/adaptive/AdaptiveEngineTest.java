package com.course.tongxiao.engine.adaptive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 6.1 G2: the AdaptiveEngine rule table (design.md §5.2).
 *
 * <p>Every test builds a synthetic event series whose expected outcome can be
 * computed by hand, so a wrong median, a wrong threshold or an off-by-one day
 * window fails the build. The negative cases are the point: they are the ones a
 * naive implementation gets wrong (fewer than 5 days, a midnight snooze counted
 * as a negative difference, a stale 8-hour-late stop polluting the median).
 */
@RunWith(AndroidJUnit4.class)
public class AdaptiveEngineTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** Monday; only relative dates matter, never the weekday itself. */
    private static final LocalDate BASE = LocalDate.parse("2026-03-02");

    private static final LocalTime MORNING = LocalTime.of(6, 40);
    private static final LocalTime LATE_NIGHT = LocalTime.of(23, 55);

    private AdaptiveEngine engine;

    @Before
    public void setUp() {
        engine = new AdaptiveEngine(ZONE);
    }

    // ---- helpers ----

    private static long epoch(LocalDate day, LocalTime time) {
        return LocalDateTime.of(day, time).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** A final stop {@code offsetMinutes} after the alarm's set time on {@code day}. */
    private static AlarmEvent finalStop(LocalDate day, LocalTime setTime, int offsetMinutes,
                                        AlarmEvent.Type type) {
        AlarmEvent event = new AlarmEvent();
        event.type = type;
        event.fireAt = epoch(day, setTime);
        event.stopAt = epoch(day, setTime.plusMinutes(offsetMinutes));
        return event;
    }

    private static AlarmEvent stopped(LocalDate day, LocalTime setTime, int offsetMinutes) {
        return finalStop(day, setTime, offsetMinutes, AlarmEvent.Type.STOPPED);
    }

    private static AlarmEvent snoozed(LocalDate day, LocalTime setTime, int offsetMinutes) {
        AlarmEvent event = new AlarmEvent();
        event.type = AlarmEvent.Type.SNOOZE;
        event.fireAt = epoch(day, setTime.plusMinutes(offsetMinutes));
        return event;
    }

    /** {@code days} days starting at {@link #BASE}, each stopping at the same offset. */
    private static List<AlarmEvent> stretch(int days, LocalTime setTime, int offsetMinutes) {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            events.add(stopped(BASE.plusDays(i), setTime, offsetMinutes));
        }
        return events;
    }

    // ---- cold start ----

    @Test
    public void coldStart_fourSampledDays_returnsNull() {
        assertNull("4 days is below the 5-day gate: keep recording, show nothing",
                engine.suggest(6, 40, stretch(4, MORNING, 30)));
    }

    @Test
    public void firedOnlyWithoutStops_returnsNull() {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AlarmEvent fired = new AlarmEvent();
            fired.type = AlarmEvent.Type.FIRED;
            fired.fireAt = epoch(BASE.plusDays(i), MORNING);
            events.add(fired);
        }
        assertNull("no final stop means no sample", engine.suggest(6, 40, events));
    }

    // ---- MOVE_EARLIER ----

    @Test
    public void fiveConsistentlyLateDays_suggestsEarlierRoundedToFiveMinutes() {
        Suggestion suggestion = engine.suggest(6, 40, stretch(5, MORNING, 18));

        assertNotNull("5 late days must produce a suggestion", suggestion);
        assertEquals(Suggestion.Type.MOVE_EARLIER, suggestion.type);
        assertEquals("one sample per day", 5, suggestion.sampleDays);
        assertEquals("E = 06:40 + 18 min", 6 * 60 + 40 + 18, suggestion.medianStopMinuteOfDay);
        // 06:58 rounds to 07:00 (nearest 5 minutes).
        assertEquals(LocalTime.of(7, 0), suggestion.suggestedTime());
        assertFalse("no snooze in this window", suggestion.hasSnoozeSamples());
    }

    @Test
    public void exactlyAtTheFifteenMinuteBoundary_isLate() {
        Suggestion suggestion = engine.suggest(6, 40, stretch(5, MORNING, 15));
        assertNotNull("E == set+15 is 'late' (rule uses >=)", suggestion);
        assertEquals(Suggestion.Type.MOVE_EARLIER, suggestion.type);
        assertEquals(LocalTime.of(6, 55), suggestion.suggestedTime());
    }

    @Test
    public void fourteenMinutesLate_isNotEnough() {
        assertNull("E == set+14 is below the +15 boundary",
                engine.suggest(6, 40, stretch(5, MORNING, 14)));
    }

    @Test
    public void fourOfFiveDaysLate_lastDayOnTime_returnsNull() {
        // The streak is broken by the most recent day, so no card yet.
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, 20));
        }
        events.add(stopped(BASE.plusDays(4), MORNING, 2));
        assertNull("the 5 most recent sampled days must ALL be late",
                engine.suggest(6, 40, events));
    }

    @Test
    public void emergencyStopCountsAsFinalStop() {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(finalStop(BASE.plusDays(i), MORNING, 25,
                    AlarmEvent.Type.EMERGENCY_STOP));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull("EMERGENCY_STOP is a final stop (design.md §3.1)", suggestion);
        assertEquals(Suggestion.Type.MOVE_EARLIER, suggestion.type);
        assertEquals(LocalTime.of(7, 5), suggestion.suggestedTime());
    }

    @Test
    public void lastStopOfTheDayIsTheSample() {
        // Each day stops twice: 07:00 then 07:25. "最终停铃" is the later one, so
        // the suggestion must be 07:25-shaped, not 07:00-shaped.
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate day = BASE.plusDays(i);
            events.add(stopped(day, MORNING, 20));
            events.add(stopped(day, MORNING, 45));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull(suggestion);
        assertEquals("one sample per day, not one per event", 5, suggestion.sampleDays);
        assertEquals(6 * 60 + 40 + 45, suggestion.medianStopMinuteOfDay);
        assertEquals(LocalTime.of(7, 25), suggestion.suggestedTime());
    }

    @Test
    public void evenSampleCount_averagesTheTwoMiddleValues() {
        int[] offsets = {20, 20, 30, 30, 40, 40};
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, offsets[i]));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull(suggestion);
        assertEquals(6, suggestion.sampleDays);
        assertEquals("median of 30 and 30", 6 * 60 + 40 + 30, suggestion.medianStopMinuteOfDay);
        assertEquals(LocalTime.of(7, 10), suggestion.suggestedTime());
    }

    // ---- midnight boundary (the classic bug) ----

    @Test
    public void stopAfterMidnight_isCountedAsLate_notAsANegativeDifference() {
        // 23:55 alarm, snoozed and stopped at 00:15 the next day. A naive
        // "minuteOfDay(stop) - minuteOfDay(set)" yields -1420 (i.e. "user stops
        // 23.7h EARLY", which would wrongly trigger MOVE_LATER).
        Suggestion suggestion = engine.suggest(23, 55, stretch(5, LATE_NIGHT, 20));

        assertNotNull("crossing midnight is a normal +20 min stop", suggestion);
        assertEquals(Suggestion.Type.MOVE_EARLIER, suggestion.type);
        assertEquals("E is 00:15, not 23:55-1420", 15, suggestion.medianStopMinuteOfDay);
        assertEquals(LocalTime.of(0, 15), suggestion.suggestedTime());
        assertEquals("still one sample per occurrence", 5, suggestion.sampleDays);
    }

    @Test
    public void earlyStopBeforeSetTimeAcrossMidnight_isLater() {
        // 00:10 alarm stopped at 23:52 the previous night would be nonsense; the
        // realistic cross-midnight early case is a 00:10 alarm stopped at 00:02.
        LocalTime justAfterMidnight = LocalTime.of(0, 10);
        Suggestion suggestion = engine.suggest(0, 10, stretch(5, justAfterMidnight, -8));
        assertNull("stop only 8 min early: below the -10 boundary", suggestion);

        Suggestion tenEarly = engine.suggest(0, 10, stretch(5, justAfterMidnight, -12));
        assertNotNull(tenEarly);
        assertEquals(Suggestion.Type.MOVE_LATER, tenEarly.type);
        assertEquals(23 * 60 + 58, tenEarly.medianStopMinuteOfDay); // 23:58 previous day
    }

    // ---- MOVE_LATER ----

    @Test
    public void consistentlyEarlyWithoutSnooze_suggestsTenMinutesLater() {
        Suggestion suggestion = engine.suggest(7, 0, stretch(5, LocalTime.of(7, 0), -20));

        assertNotNull(suggestion);
        assertEquals(Suggestion.Type.MOVE_LATER, suggestion.type);
        assertEquals(LocalTime.of(7, 10), suggestion.suggestedTime());
        assertEquals(5, suggestion.sampleDays);
        assertEquals("E = 06:40", 6 * 60 + 40, suggestion.medianStopMinuteOfDay);
    }

    @Test
    public void earlyButSnoozingInTheWindow_neverSuggestsLater() {
        List<AlarmEvent> events = stretch(5, LocalTime.of(7, 0), -20);
        events.add(snoozed(BASE.plusDays(0), LocalTime.of(7, 0), 2));
        assertNull("a snooze proves the user wants to be woken: do not delay",
                engine.suggest(7, 0, events));
    }

    @Test
    public void nineMinutesEarly_isNotEnoughForLater() {
        assertNull("E == set-9 is above the -10 boundary",
                engine.suggest(7, 0, stretch(5, LocalTime.of(7, 0), -9)));
    }

    // ---- median snooze ("M") ----

    @Test
    public void firstSnoozeMedianIsExposed() {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate day = BASE.plusDays(i);
            events.add(snoozed(day, MORNING, 10));
            events.add(stopped(day, MORNING, 20));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull(suggestion);
        assertTrue(suggestion.hasSnoozeSamples());
        assertEquals("M = 06:50", 6 * 60 + 50, suggestion.medianFirstSnoozeMinuteOfDay);
    }

    @Test
    public void multipleSnoozesPerDay_firstOneIsTheSample() {
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate day = BASE.plusDays(i);
            events.add(snoozed(day, MORNING, 12));
            events.add(snoozed(day, MORNING, 30));
            events.add(stopped(day, MORNING, 40));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull(suggestion);
        assertEquals("M = 06:52 (first snooze)", 6 * 60 + 52,
                suggestion.medianFirstSnoozeMinuteOfDay);
    }

    // ---- noisy / hostile data ----

    @Test
    public void stopHoursLater_isDroppedAsStaleInsteadOfAdvising() {
        // Phone left ringing for 8 hours: not "sleeping in", must not become a sample.
        assertNull("implausible 8h-late stops are discarded",
                engine.suggest(6, 40, stretch(5, MORNING, 8 * 60)));
    }

    @Test
    public void noOpSuggestion_isSuppressed() {
        // 13 samples where the window median is exactly the set time (8 on time,
        // then 5 late days): the streak rule fires but the advice is "06:40",
        // which the alarm already is → no card.
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, 0));
        }
        for (int i = 8; i < 13; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, 15));
        }
        assertNull("a card proposing the current time is noise",
                engine.suggest(6, 40, events));
    }

    @Test
    public void recentStreakBeatsWindowAverage() {
        // Older days were early (-40), the last 5 are late (+20): the window
        // median is -10, which would satisfy MOVE_LATER, but recent behavior wins.
        List<AlarmEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, -40));
        }
        for (int i = 5; i < 10; i++) {
            events.add(stopped(BASE.plusDays(i), MORNING, 20));
        }
        Suggestion suggestion = engine.suggest(6, 40, events);
        assertNotNull(suggestion);
        assertEquals("the 5 most recent days decide the direction",
                Suggestion.Type.MOVE_EARLIER, suggestion.type);
    }

    // ---- input hygiene ----

    @Test
    public void nullAndEmptyInputs_areNull() {
        assertNull(engine.suggest(6, 40, null));
        assertNull(engine.suggest(6, 40, new ArrayList<>()));
        assertNull("hour out of range", engine.suggest(24, 0, stretch(5, MORNING, 30)));
        assertNull("minute out of range", engine.suggest(6, 60, stretch(5, MORNING, 30)));
    }

    @Test
    public void windowStart_isFourteenDaysBack() {
        long now = 1_760_000_000_000L;
        assertEquals(now - 14L * 24L * 60L * 60L * 1000L,
                AdaptiveEngine.windowStartMillis(now));
    }

    @Test
    public void sampleDays_drivesTheColdStartHint() {
        // Nothing to suggest yet, but the UI still needs "已记录 4 天".
        List<AlarmEvent> events = stretch(4, MORNING, 30);
        assertNull(engine.suggest(6, 40, events));
        assertEquals(4, engine.sampleDays(6, 40, events));

        // A second stop on a day already sampled does not count twice.
        events.add(stopped(BASE.plusDays(3), MORNING, 45));
        assertEquals(4, engine.sampleDays(6, 40, events));

        // Implausible rows and snoozes are not samples.
        events.add(stopped(BASE.plusDays(5), MORNING, 8 * 60));
        events.add(snoozed(BASE.plusDays(6), MORNING, 10));
        assertEquals("stale rows and snoozes never count as sampled days",
                4, engine.sampleDays(6, 40, events));

        assertEquals(0, engine.sampleDays(6, 40, null));
        assertEquals(0, engine.sampleDays(6, 40, new ArrayList<>()));
    }

    @Test
    public void alarmOverloadUsesTheRowsSetTime() {
        Alarm alarm = new Alarm();
        alarm.hour = 6;
        alarm.minute = 40;

        Suggestion fromAlarm = engine.suggest(alarm, stretch(5, MORNING, 18));
        Suggestion fromTime = engine.suggest(6, 40, stretch(5, MORNING, 18));

        assertNotNull(fromAlarm);
        assertNotNull(fromTime);
        assertEquals(fromTime.type, fromAlarm.type);
        assertEquals(fromTime.suggestedTime(), fromAlarm.suggestedTime());
        assertEquals(fromTime.medianStopMinuteOfDay, fromAlarm.medianStopMinuteOfDay);
        assertEquals(5, engine.sampleDays(alarm, stretch(5, MORNING, 18)));
    }
}
