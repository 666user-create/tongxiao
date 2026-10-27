package com.course.tongxiao.engine;

import com.course.tongxiao.data.entity.Alarm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure "when does this alarm ring next" logic (design.md §5.1 + Step 2.2).
 *
 * <p>No Android APIs, so the whole decision table is testable. Comparisons are
 * done on {@link Instant}, not on local time: around a DST fall-back a local
 * "01:30" occurs twice, and picking the earlier offset could yield a moment in
 * the past (alarm fires instantly). In an overlap the later offset is chosen,
 * matching how the platform resolves alarm clock times.
 */
public class NextTriggerCalculator {

    /** Search window: covers any holiday gap (longest 2026 block is 9 days). */
    private static final int MAX_SEARCH_DAYS = 400;

    private final HolidayResolver resolver;

    public NextTriggerCalculator(HolidayResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Epoch millis of the first firing strictly after {@code now}.
     *
     * @return -1 when the alarm can never fire (e.g. CUSTOM with an empty
     *         dayMask and no matching day inside the search window).
     */
    public long nextTriggerMillis(Alarm alarm, LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        Instant nowInstant = now.atZone(zone).toInstant();
        LocalTime time = LocalTime.of(alarm.hour, alarm.minute);

        if (alarm.repeatMode == Alarm.RepeatMode.ONCE) {
            // Next occurrence of that clock time; holidays are irrelevant.
            ZonedDateTime today = resolveIfFuture(now.toLocalDate(), time, zone, nowInstant);
            if (today != null) {
                return today.toInstant().toEpochMilli();
            }
            ZonedDateTime tomorrow = resolveIfFuture(
                    now.toLocalDate().plusDays(1), time, zone, nowInstant);
            return tomorrow == null ? -1L : tomorrow.toInstant().toEpochMilli();
        }

        LocalDate date = now.toLocalDate();
        for (int i = 0; i < MAX_SEARCH_DAYS; i++, date = date.plusDays(1)) {
            if (!resolver.shouldFire(alarm, date)) {
                continue;
            }
            ZonedDateTime candidate = resolveIfFuture(date, time, zone, nowInstant);
            if (candidate != null) {
                return candidate.toInstant().toEpochMilli();
            }
        }
        return -1L;
    }

    /** Local-time view of {@link #nextTriggerMillis} (display/tests). Null when never fires. */
    public LocalDateTime nextTrigger(Alarm alarm, LocalDateTime now) {
        long millis = nextTriggerMillis(alarm, now);
        if (millis < 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    /**
     * Resolves {@code date+time} in {@code zone} and returns it only when it is
     * strictly after {@code nowInstant}.
     *
     * <p>Spring-forward gaps: the local time does not exist; the zone rules shift
     * it forward, which is what the platform reports for alarm clocks, so the
     * shifted moment is returned as-is.
     */
    private static ZonedDateTime resolveIfFuture(LocalDate date, LocalTime time,
                                                 ZoneId zone, Instant nowInstant) {
        LocalDateTime target = LocalDateTime.of(date, time);
        ZonedDateTime zoned = target.atZone(zone);
        if (zoned.toLocalDateTime().equals(target)
                && zone.getRules().getValidOffsets(target).size() > 1) {
            // Autumn overlap: both offsets exist, take the later one.
            zoned = zoned.withLaterOffsetAtOverlap();
        }
        return zoned.toInstant().isAfter(nowInstant) ? zoned : null;
    }
}
