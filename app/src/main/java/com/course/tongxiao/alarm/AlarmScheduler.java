package com.course.tongxiao.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.NextTriggerCalculator;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Registers/cancels system alarms (design.md §2.2, §6).
 *
 * <p>Design points that matter in the defence:
 * <ul>
 *   <li>{@code setAlarmClock} is used instead of {@code setExact*}: it is the
 *       alarm-class API, shows the system alarm icon and gets the strongest
 *       Doze/saver treatment.</li>
 *   <li>requestCode = {@link Alarm#id}, so re-scheduling an alarm REPLACES its
 *       registration instead of stacking a second one (idempotency).</li>
 *   <li>All PendingIntents use {@code FLAG_IMMUTABLE} (required on API 31+).</li>
 *   <li>Exact-alarm permission is checked; on refusal the app falls back to
 *       {@code setAndAllowWhileIdle} (inexact but never silently drops the
 *       alarm) — "宁响勿哑" (design.md §8).</li>
 * </ul>
 *
 * <p>All methods are blocking-free (AlarmManager is a system call) but must not
 * be invoked from the main thread when they touch the repository.
 */
public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";

    private final Context appContext;
    private final AlarmManager alarmManager;
    private final AlarmRepository repository;
    private final NextTriggerCalculator calculator;

    public AlarmScheduler(Context context, AlarmRepository repository, HolidayResolver resolver) {
        this.appContext = context.getApplicationContext();
        this.alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        this.repository = repository;
        this.calculator = new NextTriggerCalculator(resolver);
    }

    /**
     * Schedules the alarm's next occurrence.
     *
     * @return epoch millis actually registered, or -1 when the alarm can never
     *         fire (empty custom day mask etc.) — caller may surface that.
     */
    public long schedule(Alarm alarm) {
        long triggerAt = calculator.nextTriggerMillis(alarm, LocalDateTime.now());
        if (triggerAt <= 0) {
            Log.w(TAG, "alarm " + alarm.id + " has no future occurrence");
            cancel(alarm);
            return -1L;
        }

        PendingIntent operation = firePendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent showIntent = showPendingIntent(alarm);

        if (canScheduleExactAlarms()) {
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
        } else {
            // No exact-alarm permission: still deliver, just not to the second.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
            Log.w(TAG, "exact alarms not granted; used setAndAllowWhileIdle");
        }
        return triggerAt;
    }

    public void cancel(Alarm alarm) {
        PendingIntent operation = firePendingIntent(alarm, 0);
        if (operation != null) {
            alarmManager.cancel(operation);
            operation.cancel();
        }
    }

    /**
     * Boot / time-change / manual-refresh path: cancel every known registration
     * (including disabled alarms, whose stale registrations must not survive),
     * then schedule the enabled ones.
     *
     * @return number of alarms scheduled.
     */
    public int rescheduleAll() {
        List<Alarm> all = repository.getAll();
        for (Alarm alarm : all) {
            cancel(alarm);
        }
        int scheduled = 0;
        for (Alarm alarm : all) {
            if (alarm.enabled && schedule(alarm) > 0) {
                scheduled++;
            }
        }
        return scheduled;
    }

    /** True when the platform will honour exact/clock alarms for this app. */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // pre-12: no restriction
        }
        return alarmManager.canScheduleExactAlarms();
    }

    private PendingIntent firePendingIntent(Alarm alarm, int extraFlags) {
        Intent intent = new Intent(appContext, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        return PendingIntent.getBroadcast(
                appContext, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | extraFlags);
    }

    /** Tap the system alarm icon → open the app. */
    private PendingIntent showPendingIntent(Alarm alarm) {
        Intent intent = appContext.getPackageManager()
                .getLaunchIntentForPackage(appContext.getPackageName());
        if (intent == null) {
            return null;
        }
        return PendingIntent.getActivity(
                appContext, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
