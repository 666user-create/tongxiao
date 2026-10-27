package com.course.tongxiao.alarm;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trigger entry point (design.md §2.2).
 *
 * <p>{@code onReceive} runs on the main thread, so everything is handed to an
 * executor via {@code goAsync()}. The work is split in two strictly separated
 * phases so that a failure can only ever degrade the outcome, never crash it:
 * <ol>
 *   <li><b>decision</b> — read the alarm, validate its uid, apply the weekday
 *       rule. Any failure here falls back to "ring" (宁响勿哑);</li>
 *   <li><b>ring</b> — start the foreground service exactly once, with its own
 *       try/catch (never retried from inside a catch, which could re-throw).</li>
 * </ol>
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    /** One shared executor: receiver instances are short-lived objects. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmIntents.ACTION_FIRE_ALARM.equals(intent.getAction())) {
            return;
        }
        int alarmId = intent.getIntExtra(AlarmIntents.EXTRA_ALARM_ID, -1);
        if (alarmId <= 0) {
            Log.w(TAG, "fire request without a valid alarm id");
            return;
        }
        final boolean fromSnooze = intent.getBooleanExtra(AlarmIntents.EXTRA_FROM_SNOOZE, false);
        final String expectedUid = intent.getStringExtra(AlarmIntents.EXTRA_ALARM_UID);
        final long fireAt = System.currentTimeMillis();
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();

        WORKER.execute(() -> {
            boolean shouldRing = true; // 宁响勿哑 default
            try {
                shouldRing = decide(appContext, alarmId, expectedUid, fromSnooze, fireAt);
            } catch (RuntimeException e) {
                Log.e(TAG, "decision failed, ringing anyway", e);
            } finally {
                if (shouldRing) {
                    ring(appContext, alarmId, expectedUid);
                }
                pending.finish();
            }
        });
    }

    /**
     * @return true when the alarm must ring now.
     */
    private boolean decide(Context appContext, int alarmId, String expectedUid,
                           boolean fromSnooze, long fireAt) {
        TongxiaoDb db = TongxiaoDb.get(appContext);
        AlarmRepository repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(appContext));

        Alarm alarm = db.alarmDao().getById(alarmId);
        if (alarm == null) {
            Log.w(TAG, "alarm " + alarmId + " no longer exists");
            return false;
        }

        // Stale registration guard: after a destructive DB rebuild ids restart at
        // 1, so an old PendingIntent could otherwise fire a DIFFERENT alarm.
        if (!TextUtils.isEmpty(expectedUid) && !expectedUid.equals(alarm.uid)) {
            Log.w(TAG, "stale trigger: expected uid " + expectedUid + " but row has " + alarm.uid);
            return false;
        }

        if (fromSnooze) {
            // The user explicitly asked to be woken again; holiday/weekday rules
            // must not swallow that request (it may cross midnight into a holiday).
            recordSnoozeFire(db, alarm, fireAt);
            return true;
        }

        if (!resolver.shouldFire(alarm, LocalDate.now())) {
            RingActions actions = new RingActions(
                    db, repository, new AlarmScheduler(appContext, repository, resolver));
            actions.onSkipped(alarm, fireAt, "rule: holiday/not-today");
            return false;
        }
        return true;
    }

    private void recordSnoozeFire(TongxiaoDb db, Alarm alarm, long fireAt) {
        AlarmEvent event = new AlarmEvent();
        event.alarmId = alarm.id;
        event.fireAt = fireAt;
        event.type = AlarmEvent.Type.FIRED;
        event.meta = "{\"source\":\"snooze-refire\"}";
        try {
            db.alarmEventDao().insert(event);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not record snooze refire", e);
        }
    }

    /** Starts the ring service; a failure here must not re-throw out of the executor. */
    private static void ring(Context appContext, int alarmId, String alarmUid) {
        Intent ringIntent = new Intent(appContext, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, alarmId);
        if (!TextUtils.isEmpty(alarmUid)) {
            ringIntent.putExtra(AlarmIntents.EXTRA_ALARM_UID, alarmUid);
        }
        try {
            ContextCompat.startForegroundService(appContext, ringIntent);
        } catch (RuntimeException startRefused) {
            // e.g. ForegroundServiceStartNotAllowedException when the inexact
            // fallback did not grant the FGS exemption. Degrade to a plain
            // notification instead of crashing or retrying the same call.
            Log.e(TAG, "foreground ring refused for alarm " + alarmId, startRefused);
            notifyFallback(appContext, alarmId);
        }
    }

    private static void notifyFallback(Context appContext, int alarmId) {
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "fallback notification blocked: POST_NOTIFICATIONS denied");
                return;
            }
            androidx.core.app.NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(appContext, "alarm_ringing")
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle(appContext.getString(R.string.ring_notification_title))
                            .setContentText(appContext.getString(R.string.ring_fallback_text))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true);
            NotificationManagerCompat.from(appContext).notify(9000 + alarmId, builder.build());
        } catch (RuntimeException e) {
            Log.e(TAG, "fallback notification also failed", e);
        }
    }
}
