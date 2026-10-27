package com.course.tongxiao.alarm;

/** Intent contract between AlarmManager, AlarmReceiver, RingService and the stop screen. */
public final class AlarmIntents {

    /** Fired by AlarmManager.setAlarmClock at the trigger moment. */
    public static final String ACTION_FIRE_ALARM = "com.course.tongxiao.action.FIRE_ALARM";

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_ALARM_UID = "alarm_uid";

    /**
     * True when this fire is a snooze re-arm (design.md §2.2): the user already
     * asked to be woken 10 minutes later, so the holiday/weekday rule must NOT be
     * re-applied — otherwise a snooze over midnight into a holiday is swallowed.
     */
    public static final String EXTRA_FROM_SNOOZE = "from_snooze";

    /** Wall-clock time the ring actually started; lets STOPPED events carry a duration. */
    public static final String EXTRA_RING_STARTED_AT = "ring_started_at";

    private AlarmIntents() {
    }
}
