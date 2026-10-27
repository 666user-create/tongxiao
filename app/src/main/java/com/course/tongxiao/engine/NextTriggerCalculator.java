package com.course.tongxiao.engine;

import com.course.tongxiao.data.entity.Alarm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Pure "when does this alarm ring next" logic (design.md §5.1 + Step 2.2).
 *
 * <p>Kept free of Android APIs so the whole decision table is unit-testable on
 * the JVM side of the emulator run. The scheduler turns the result into epoch
 * millis and hands it to AlarmManager.
 */
public class NextTriggerCalculator {

    /** Search window: covers any holiday gap (longest 2026 block is 9 days). */
    private static final int MAX_SEARCH_DAYS = 400;

    private final HolidayResolver resolver;

    public NextTriggerCalculator(HolidayResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * First firing moment strictly after {@code now}.
     *
     * <p>ONCE means "the next occurrence of that clock time" (today if still in
     * the future, otherwise tomorrow) and ignores holiday skipping — a one-shot
     * reminder is explicit user intent; the UI offers to defer it instead.
     *
     * @return null only when the alarm can never fire (e.g. CUSTOM with an empty
     *         dayMask and no matching day inside the window).
     */
    public LocalDateTime nextTrigger(Alarm alarm, LocalDateTime now) {
        LocalTime time = LocalTime.of(alarm.hour, alarm.minute);

        if (alarm.repeatMode == Alarm.RepeatMode.ONCE) {
            LocalDateTime today = LocalDateTime.of(now.toLocalDate(), time);
            return today.isAfter(now) ? today : today.plusDays(1);
        }

        LocalDate date = now.toLocalDate();
        for (int i = 0; i < MAX_SEARCH_DAYS; i++, date = date.plusDays(1)) {
            if (!resolver.shouldFire(alarm, date)) {
                continue;
            }
            LocalDateTime candidate = LocalDateTime.of(date, time);
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return null;
    }

    /** Epoch millis for AlarmManager at the system default zone. */
    public long nextTriggerMillis(Alarm alarm, LocalDateTime now) {
        LocalDateTime next = nextTrigger(alarm, now);
        if (next == null) {
            return -1L;
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
