package com.course.tongxiao.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.WorkerThread;

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
 *   <li>{@code setAlarmClock} instead of {@code setExact*}: alarm-class API,
 *       shows the system alarm icon, strongest Doze/standby treatment.</li>
 *   <li>requestCode = {@link Alarm#id}: re-scheduling REPLACES a registration
 *       instead of stacking a second one (idempotent).</li>
 *   <li>All PendingIntents use {@code FLAG_IMMUTABLE} (required on API 31+).</li>
 *   <li>Exact-alarm permission is checked; on refusal it degrades to
 *       {@code setAndAllowWhileIdle} (inexact but never silently dropped).</li>
 * </ul>
 *
 * <p>Threading: {@link #schedule} / {@link #cancel} only talk to AlarmManager
 * (any thread), but {@link #rescheduleAll()} reads Room and is
 * {@link WorkerThread} — a BOOT/TIME_SET receiver must hand it to an executor.
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
     * Schedules the alarm's next occurrence. Disabled alarms are cancelled and
     * never registered (guards against "save a disabled alarm" paths).
     *
     * @return epoch millis registered, or -1 when the alarm is disabled or can
     *         never fire. With an inexact fallback the platform may fire later
     *         than the returned value.
     */
    @AnyThread
    public long schedule(Alarm alarm) {
        if (!alarm.enabled) {
            cancel(alarm);
            return -1L;
        }
        long triggerAt;
        try {
            triggerAt = calculator.nextTriggerMillis(alarm, LocalDateTime.now());
        } catch (RuntimeException badData) {
            // e.g. hour/minute out of range from imported or remote data
            Log.e(TAG, "alarm " + alarm.id + " has invalid time", badData);
            cancel(alarm);
            return -1L;
        }
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

    @AnyThread
    public void cancel(Alarm alarm) {
        PendingIntent operation = firePendingIntent(alarm, PendingIntent.FLAG_NO_CREATE);
        if (operation != null) { // FLAG_NO_CREATE: no record invented for never-scheduled alarms
            alarmManager.cancel(operation);
            operation.cancel();
        }
    }

    /**
     * Boot / time-change / manual-refresh path: re-register every enabled alarm
     * and drop stale registrations of disabled ones.
     *
     * <p>Per-alarm isolation on purpose: one corrupt row must not abort the sweep
     * and leave every alarm unregistered ("宁响勿哑", design.md §8).
     *
     * @return number of alarms successfully scheduled.
     */
    @WorkerThread
    public int rescheduleAll() {
        List<Alarm> all = repository.getAll();
        int scheduled = 0;
        for (Alarm alarm : all) {
            try {
                if (alarm.enabled) {
                    if (schedule(alarm) > 0) {
                        scheduled++;
                    }
                } else {
                    cancel(alarm);
                }
            } catch (RuntimeException one) {
                Log.e(TAG, "reschedule failed for alarm " + alarm.id, one);
            }
        }
        return scheduled;
    }

    /** True when the platform will honour exact/clock alarms for this app. */
    @AnyThread
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
