package com.course.tongxiao.alarm;

/** Intent contract between AlarmManager, AlarmReceiver and RingService (design.md §2.2). */
public final class AlarmIntents {

    /** Fired by AlarmManager.setAlarmClock at the trigger moment. */
    public static final String ACTION_FIRE_ALARM = "com.course.tongxiao.action.FIRE_ALARM";

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_ALARM_UID = "alarm_uid";

    private AlarmIntents() {
    }
}
