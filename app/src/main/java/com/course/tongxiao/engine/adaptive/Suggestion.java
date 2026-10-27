package com.course.tongxiao.engine.adaptive;

import androidx.annotation.NonNull;

import java.time.LocalTime;
import java.util.Locale;

/**
 * One adaptive suggestion produced by {@link AdaptiveEngine} (design.md §5.2).
 *
 * <p>A suggestion is <b>advisory only</b>: the engine never writes to
 * {@code alarms}. The edit screen shows a card and the user either accepts
 * (which rewrites {@code alarm.hour/minute} through the normal save path) or
 * ignores it. That is the "只建议不自动改" trust boundary.
 *
 * <p>"Median" fields are minutes-since-midnight (0..1439) so the UI can render
 * them with a clock formatter; {@link #medianStopMinuteOfDay} is the "E" of
 * design.md §5.2 and is what the suggested time is derived from.
 */
public final class Suggestion {

    public enum Type {
        /** User consistently stops later than the set time → suggest an earlier alarm. */
        MOVE_EARLIER,
        /** User consistently stops before the set time and never snoozes → suggest a later alarm. */
        MOVE_LATER
    }

    /** Marker for {@link #medianFirstSnoozeMinuteOfDay} when the window has no snooze sample. */
    public static final int NO_SNOOZE_SAMPLE = -1;

    @NonNull
    public final Type type;

    /** Suggested clock time (already rounded/wrapped, so 0 <= hour <= 23). */
    public final int hour;
    public final int minute;

    /** Distinct days that contributed a final-stop sample; always >= AdaptiveEngine#MIN_SAMPLE_DAYS. */
    public final int sampleDays;

    /** Median final-stop clock minute ("E", 0..1439). */
    public final int medianStopMinuteOfDay;

    /** Median first-snooze clock minute ("M", 0..1439), or {@link #NO_SNOOZE_SAMPLE}. */
    public final int medianFirstSnoozeMinuteOfDay;

    public Suggestion(@NonNull Type type, int hour, int minute, int sampleDays,
                      int medianStopMinuteOfDay, int medianFirstSnoozeMinuteOfDay) {
        this.type = type;
        this.hour = hour;
        this.minute = minute;
        this.sampleDays = sampleDays;
        this.medianStopMinuteOfDay = medianStopMinuteOfDay;
        this.medianFirstSnoozeMinuteOfDay = medianFirstSnoozeMinuteOfDay;
    }

    /** True when the window contains snooze samples (i.e. "M" is meaningful). */
    public boolean hasSnoozeSamples() {
        return medianFirstSnoozeMinuteOfDay >= 0;
    }

    /** Suggested time as a clock value (UI formatting / tests). */
    @NonNull
    public LocalTime suggestedTime() {
        return LocalTime.of(hour, minute);
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US, "Suggestion{%s %02d:%02d, days=%d, E=%d, M=%d}",
                type, hour, minute, sampleDays, medianStopMinuteOfDay,
                medianFirstSnoozeMinuteOfDay);
    }
}
