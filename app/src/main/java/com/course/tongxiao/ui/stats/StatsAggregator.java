package com.course.tongxiao.ui.stats;

import com.course.tongxiao.data.entity.AlarmEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 6.3 statistics aggregation (design.md §7 ⑥).
 *
 * <p>Deliberately free of Android types: the three numbers on the stats screen
 * ("wake-up trend", "snooze ranking", "muted by roommates") are pure functions
 * of the {@code alarm_events} rows, so they are asserted in unit tests without
 * an emulator, a database or a drawn view.
 *
 * <p>Definitions (single source of truth, the UI only renders them):
 * <ul>
 *   <li><b>wake-up trend</b> — per local day, the earliest {@link AlarmEvent.Type#STOPPED}
 *       {@code stopAt}. First stop of the day is the moment the user actually got
 *       up; later stops are snooze cycles. Days without a stop produce no point
 *       (the line skips the gap instead of inventing a value).</li>
 *   <li><b>snooze ranking</b> — {@link AlarmEvent.Type#SNOOZE} events grouped by
 *       {@code alarmId}, descending, top {@value #SNOOZE_TOP_N}.</li>
 *   <li><b>muted count</b> — STOPPED events whose {@code meta} carries the
 *       {@code remote} marker, i.e. a roommate muted this ringing alarm
 *       (design.md §3.2 "免确认 + 留痕"). Emergency stops count as well because
 *       {@code RingActions} logs them as STOPPED + source.</li>
 * </ul>
 *
 * <p>Caller owns the query window ({@code AlarmEventDao.getSince}); this class
 * only clips the trend to {@code [today-13, today]}.
 */
public final class StatsAggregator {

    /** X axis of the trend chart: the last 14 local days including today. */
    public static final int TREND_DAYS = 14;

    public static final int SNOOZE_TOP_N = 3;

    /** {@code meta} marker written by the remote-mute path (RingActions "remote:<deviceId>"). */
    public static final String REMOTE_META_MARKER = "remote";

    private StatsAggregator() {
    }

    /** One plotted day: which day, and the wake-up time as minutes since local midnight. */
    public static final class WakePoint {

        public final LocalDate date;
        public final int minuteOfDay;

        public WakePoint(LocalDate date, int minuteOfDay) {
            this.date = date;
            this.minuteOfDay = minuteOfDay;
        }

        @Override
        public String toString() {
            return date + "=" + (minuteOfDay / 60) + ":" + String.format(Locale.ROOT, "%02d", minuteOfDay % 60);
        }
    }

    /** One row of the snooze ranking. */
    public static final class SnoozeRow {

        public final int alarmId;
        public final int count;

        public SnoozeRow(int alarmId, int count) {
            this.alarmId = alarmId;
            this.count = count;
        }

        @Override
        public String toString() {
            return "alarm" + alarmId + "=" + count;
        }
    }

    /** Everything the stats screen renders, computed in one pass. */
    public static final class Stats {

        public final List<WakePoint> trend;
        public final List<SnoozeRow> snoozeTop;
        public final int remoteMutedCount;

        Stats(List<WakePoint> trend, List<SnoozeRow> snoozeTop, int remoteMutedCount) {
            this.trend = trend;
            this.snoozeTop = snoozeTop;
            this.remoteMutedCount = remoteMutedCount;
        }
    }

    /**
     * @param events rows from {@code alarm_events} (null and null elements tolerated:
     *               the screen must never crash on a half-read log).
     * @param today  the chart's right edge / "today" for the 14-day window.
     */
    public static Stats aggregate(List<AlarmEvent> events, LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("today == null");
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate windowStart = today.minusDays(TREND_DAYS - 1L);

        Map<LocalDate, Long> earliestStopByDay = new HashMap<>();
        Map<Integer, Integer> snoozeByAlarm = new HashMap<>();
        int remoteMuted = 0;

        if (events != null) {
            for (AlarmEvent event : events) {
                if (event == null || event.type == null) {
                    continue;
                }
                if (event.type == AlarmEvent.Type.STOPPED) {
                    if (event.stopAt > 0) {
                        LocalDate day = Instant.ofEpochMilli(event.stopAt)
                                .atZone(zone).toLocalDate();
                        if (!day.isBefore(windowStart) && !day.isAfter(today)) {
                            Long previous = earliestStopByDay.get(day);
                            if (previous == null || event.stopAt < previous) {
                                earliestStopByDay.put(day, event.stopAt);
                            }
                        }
                    }
                    // Counted even when stopAt is unusable: the mute DID happen.
                    if (isRemoteMute(event.meta)) {
                        remoteMuted++;
                    }
                } else if (event.type == AlarmEvent.Type.SNOOZE) {
                    snoozeByAlarm.merge(event.alarmId, 1, Integer::sum);
                }
            }
        }

        List<WakePoint> trend = new ArrayList<>(earliestStopByDay.size());
        for (Map.Entry<LocalDate, Long> entry : earliestStopByDay.entrySet()) {
            LocalTime stop = Instant.ofEpochMilli(entry.getValue()).atZone(zone).toLocalTime();
            trend.add(new WakePoint(entry.getKey(), stop.getHour() * 60 + stop.getMinute()));
        }
        trend.sort(Comparator.comparing(point -> point.date));

        List<SnoozeRow> ranking = new ArrayList<>(snoozeByAlarm.size());
        for (Map.Entry<Integer, Integer> entry : snoozeByAlarm.entrySet()) {
            ranking.add(new SnoozeRow(entry.getKey(), entry.getValue()));
        }
        // Descending count; alarmId breaks ties so the order is deterministic
        // (a HashMap iteration order would make the screen and its test flaky).
        ranking.sort((left, right) -> {
            int byCount = Integer.compare(right.count, left.count);
            return byCount != 0 ? byCount : Integer.compare(left.alarmId, right.alarmId);
        });
        if (ranking.size() > SNOOZE_TOP_N) {
            ranking = new ArrayList<>(ranking.subList(0, SNOOZE_TOP_N));
        }

        return new Stats(trend, ranking, remoteMuted);
    }

    /** True when a roommate muted the alarm (meta is JSON, e.g. {"source":"remote:abc"}). */
    public static boolean isRemoteMute(String meta) {
        return meta != null
                && meta.toLowerCase(Locale.ROOT).contains(REMOTE_META_MARKER);
    }
}
