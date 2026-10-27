package com.course.tongxiao.engine.adaptive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Behavior-adaptive suggestion engine (design.md §5.2, plan.md Step 6.1).
 *
 * <p><b>Input</b>: the alarm's events for the last {@value #WINDOW_DAYS} days
 * ({@code AlarmEventDao.getRecent(alarmId, since)} with
 * {@code since = }{@link #windowStartMillis(long)}), plus the alarm's set time.
 * <b>Output</b>: at most one {@link Suggestion}, or {@code null} when there is
 * nothing trustworthy to say. The engine is pure (no Android APIs, no DB, no
 * writes) so the whole rule table is testable.
 *
 * <p><b>Two windows</b> are computed, both as a signed offset from the alarm's
 * set time so that a stop/snooze across midnight is never mistaken for a
 * negative difference:
 * <ul>
 *   <li><b>E</b> — per day, the moment of the LAST final stop
 *       ({@code STOPPED}/{@code EMERGENCY_STOP}); the median of those offsets
 *       over the window, reported back as a clock minute.</li>
 *   <li><b>M</b> — per day, the moment of the FIRST snooze; same treatment.</li>
 * </ul>
 *
 * <p><b>Rules</b> (evaluated in order, first match wins):
 * <ol>
 *   <li>fewer than {@value #MIN_SAMPLE_DAYS} sampled days → {@code null}
 *       (cold start, "继续记录中");</li>
 *   <li>the {@value #CONSECUTIVE_DAYS} most recent sampled days all stop at
 *       least {@value #EARLIER_BOUNDARY_MIN} min <i>after</i> the set time →
 *       {@link Suggestion.Type#MOVE_EARLIER} at E rounded to
 *       {@value #SUGGEST_ROUNDING_MIN} minutes;</li>
 *   <li>E is at least {@value #LATER_BOUNDARY_MIN} min <i>before</i> the set
 *       time and the window contains no snooze at all →
 *       {@link Suggestion.Type#MOVE_LATER} at the set time +
 *       {@value #LATER_STEP_MIN} min.</li>
 * </ol>
 *
 * <p><b>Why "consecutive" means consecutive samples, not consecutive calendar
 * days</b>: a WORKDAY_SMART alarm only records Monday–Friday, so a literal
 * 5-calendar-day streak could never be reached without a weekend alarm. The
 * rule therefore looks at the 5 most recent days that actually produced a
 * final stop — an alarm that was skipped for a holiday has no sample and must
 * not break the streak.
 *
 * <p>Rule order matters: recent behavior beats the window average. When older
 * days were early but the last five are consistently late, "move earlier" is
 * the useful advice, and the MOVE_LATER rule may not fire.
 *
 * <p>Deliberately no I/O and no clock: the caller supplies the window via the
 * DAO query, and tests can pin the zone with {@link #AdaptiveEngine(ZoneId)}.
 */
public class AdaptiveEngine {

    /** Cold start gate: fewer sampled days than this → no card at all. */
    public static final int MIN_SAMPLE_DAYS = 5;

    /** Look-back window for the DAO query. */
    public static final int WINDOW_DAYS = 14;

    /** A final stop this much later than the set time counts as "late". */
    public static final int EARLIER_BOUNDARY_MIN = 15;

    /** A final stop this much earlier than the set time counts as "early". */
    public static final int LATER_BOUNDARY_MIN = 10;

    /** How many of the most recent sampled days must agree with the earlier rule. */
    public static final int CONSECUTIVE_DAYS = 5;

    /** MOVE_LATER suggests exactly this much extra sleep (design.md §5.2). */
    public static final int LATER_STEP_MIN = 10;

    /** MOVE_EARLIER rounds the suggested time to this granularity. */
    public static final int SUGGEST_ROUNDING_MIN = 5;

    private static final int DAY_MINUTES = 24 * 60;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * Sanity bound for a single sample. A stop more than 3h away from the set
     * time is not "sleeping in", it is a stale row (phone left ringing, alarm
     * never stopped, clock changed): feeding it to the median would silently
     * corrupt the advice, so the day is dropped instead.
     */
    private static final int MAX_PLAUSIBLE_OFFSET_MIN = 180;

    private final ZoneId zone;

    /** Production constructor: device time zone decides what "a day" is. */
    public AdaptiveEngine() {
        this(ZoneId.systemDefault());
    }

    /** Test seam: pin the zone so day bucketing is deterministic. */
    public AdaptiveEngine(@NonNull ZoneId zone) {
        this.zone = zone;
    }

    /** {@code since} argument for {@code AlarmEventDao.getRecent} (inclusive window start). */
    public static long windowStartMillis(long nowMillis) {
        return nowMillis - WINDOW_DAYS * DAY_MILLIS;
    }

    /** Convenience overload for the edit screen: takes the set time from the row. */
    @Nullable
    public Suggestion suggest(@NonNull Alarm alarm, @Nullable List<AlarmEvent> events) {
        return suggest(alarm.hour, alarm.minute, events);
    }

    /**
     * @param setHour   0..23, the alarm's current hour
     * @param setMinute 0..59, the alarm's current minute
     * @param events    events inside the window, any order (the DAO sorts by
     *                  fireAt, but the engine does not rely on it)
     * @return the suggestion, or {@code null} when none is justified
     */
    @Nullable
    public Suggestion suggest(int setHour, int setMinute, @Nullable List<AlarmEvent> events) {
        if (events == null || events.isEmpty() || !isValidSetTime(setHour, setMinute)) {
            return null;
        }
        final int setMinutes = setHour * 60 + setMinute;
        Samples samples = collect(setMinutes, events);

        int sampleDays = samples.stopOffsetsByDay.size();
        if (sampleDays < MIN_SAMPLE_DAYS) {
            return null; // cold start: keep recording, show nothing
        }

        int medianStopOffset = median(samples.stopOffsetsByDay.values());
        int medianStopMinute = wrapToDay(setMinutes + medianStopOffset);
        int medianSnoozeMinute = samples.snoozeOffsetsByDay.isEmpty()
                ? Suggestion.NO_SNOOZE_SAMPLE
                : wrapToDay(setMinutes + median(samples.snoozeOffsetsByDay.values()));

        if (recentDaysAllAtLeast(samples.stopOffsetsByDay, CONSECUTIVE_DAYS, EARLIER_BOUNDARY_MIN)) {
            int target = wrapToDay(roundToStep(setMinutes + medianStopOffset, SUGGEST_ROUNDING_MIN));
            if (target == setMinutes) {
                // A card proposing the time the alarm already has is noise.
                return null;
            }
            return new Suggestion(Suggestion.Type.MOVE_EARLIER,
                    target / 60, target % 60, sampleDays, medianStopMinute, medianSnoozeMinute);
        }

        if (!samples.windowHasSnooze && medianStopOffset <= -LATER_BOUNDARY_MIN) {
            int target = wrapToDay(setMinutes + LATER_STEP_MIN);
            return new Suggestion(Suggestion.Type.MOVE_LATER,
                    target / 60, target % 60, sampleDays, medianStopMinute, medianSnoozeMinute);
        }

        return null;
    }

    /** Convenience overload: distinct sampled days for the row's set time. */
    public int sampleDays(@NonNull Alarm alarm, @Nullable List<AlarmEvent> events) {
        return sampleDays(alarm.hour, alarm.minute, events);
    }

    /**
     * Distinct days that produced a usable final-stop sample, capped by
     * {@link #MIN_SAMPLE_DAYS}.
     *
     * <p>Exists for the cold-start hint design.md §5.2 asks for ("继续记录中"):
     * when {@link #suggest} returns null the UI can still tell the user how far
     * along the recording is. It never changes alarm data.
     */
    public int sampleDays(int setHour, int setMinute, @Nullable List<AlarmEvent> events) {
        if (events == null || events.isEmpty() || !isValidSetTime(setHour, setMinute)) {
            return 0;
        }
        return collect(setHour * 60 + setMinute, events).stopOffsetsByDay.size();
    }

    private static boolean isValidSetTime(int setHour, int setMinute) {
        return setHour >= 0 && setHour <= 23 && setMinute >= 0 && setMinute <= 59;
    }

    /**
     * Sorts the window into per-day samples. Both maps are keyed by local day and
     * hold a signed offset from the set time; TreeMap gives ascending date order
     * for the "most recent N" rule.
     */
    @NonNull
    private Samples collect(int setMinutes, @Nullable List<AlarmEvent> events) {
        Samples samples = new Samples();
        if (events == null) {
            return samples;
        }
        for (AlarmEvent event : events) {
            if (event == null || event.type == null) {
                continue;
            }
            if (isFinalStop(event.type)) {
                if (event.stopAt <= 0) {
                    continue; // nothing usable was recorded
                }
                int offset = offsetFromSetTime(event.stopAt, setMinutes);
                if (Math.abs(offset) > MAX_PLAUSIBLE_OFFSET_MIN) {
                    continue;
                }
                LocalDate day = toLocalDate(event.stopAt);
                Integer previous = samples.stopOffsetsByDay.get(day);
                // "最终停铃" = the last stop of that day (a re-fire can add one).
                if (previous == null || offset > previous) {
                    samples.stopOffsetsByDay.put(day, offset);
                }
            } else if (event.type == AlarmEvent.Type.SNOOZE) {
                samples.windowHasSnooze = true;
                if (event.fireAt <= 0) {
                    continue;
                }
                int offset = offsetFromSetTime(event.fireAt, setMinutes);
                if (Math.abs(offset) > MAX_PLAUSIBLE_OFFSET_MIN) {
                    continue;
                }
                LocalDate day = toLocalDate(event.fireAt);
                Integer previous = samples.snoozeOffsetsByDay.get(day);
                // "首次贪睡" = the earliest snooze of that day.
                if (previous == null || offset < previous) {
                    samples.snoozeOffsetsByDay.put(day, offset);
                }
            }
        }
        return samples;
    }

    /** Per-day offsets collected from one event series. */
    private static final class Samples {
        final Map<LocalDate, Integer> stopOffsetsByDay = new TreeMap<>();
        final Map<LocalDate, Integer> snoozeOffsetsByDay = new TreeMap<>();
        boolean windowHasSnooze;
    }

    private static boolean isFinalStop(@NonNull AlarmEvent.Type type) {
        return type == AlarmEvent.Type.STOPPED || type == AlarmEvent.Type.EMERGENCY_STOP;
    }

    /** Signed distance of a moment from the set time on the 24h clock, in [-720, 720). */
    private int offsetFromSetTime(long epochMillis, int setMinutes) {
        int minuteOfDay = Instant.ofEpochMilli(epochMillis)
                .atZone(zone).toLocalTime().toSecondOfDay() / 60;
        return circularOffset(minuteOfDay, setMinutes);
    }

    /**
     * Midnight-safe difference: 23:55 → 00:05 is +10, never -1430 and never a
     * negative "sleep-in" (design.md §5.2 boundary case).
     */
    private static int circularOffset(int minuteOfDay, int referenceMinutes) {
        int difference = minuteOfDay - referenceMinutes;
        return difference - DAY_MINUTES * Math.floorDiv(difference + DAY_MINUTES / 2, DAY_MINUTES);
    }

    /** Normalizes an offset day back into 0..1439 (23:55 + 20 min → 15 = 00:15). */
    private static int wrapToDay(int minutes) {
        return ((minutes % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES;
    }

    /** Rounds to the nearest {@code step}, ties toward the earlier time. */
    private static int roundToStep(int minutes, int step) {
        return Math.floorDiv(minutes + step / 2, step) * step;
    }

    private LocalDate toLocalDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    /** True when the newest {@code days} sampled days all stop at least {@code threshold} late. */
    private static boolean recentDaysAllAtLeast(Map<LocalDate, Integer> offsetsByDay,
                                                int days, int threshold) {
        if (offsetsByDay.size() < days) {
            return false;
        }
        List<Integer> offsets = new ArrayList<>(offsetsByDay.values()); // ascending date order
        for (int i = offsets.size() - days; i < offsets.size(); i++) {
            if (offsets.get(i) < threshold) {
                return false;
            }
        }
        return true;
    }

    /** Median of a non-empty offset series; an even count averages the two middle values. */
    private static int median(Collection<Integer> offsets) {
        List<Integer> sorted = new ArrayList<>(offsets);
        Collections.sort(sorted);
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        long sum = (long) sorted.get(middle - 1) + sorted.get(middle);
        return (int) Math.round(sum / 2.0);
    }
}
