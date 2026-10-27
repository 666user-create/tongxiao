package com.course.tongxiao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trigger entry point (design.md §2.2).
 *
 * <p>{@code onReceive} runs on the main thread, so everything here is handed to
 * an executor via {@code goAsync()}. Two outcomes:
 * <ul>
 *   <li>rule says "do not ring today" (holiday while the system alarm was armed
 *       from an older schedule) → record SKIPPED_HOLIDAY and re-arm the next
 *       valid day;</li>
 *   <li>otherwise → start {@link RingService} as a foreground service.</li>
 * </ul>
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
        final long fireAt = System.currentTimeMillis();
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();

        WORKER.execute(() -> {
            try {
                handleFire(appContext, alarmId, fireAt);
            } catch (RuntimeException e) {
                // Never let a data problem swallow the ring silently (design.md §8):
                // fall back to ringing anyway.
                Log.e(TAG, "fire handling failed, ringing anyway", e);
                startRing(appContext, alarmId);
            } finally {
                pending.finish();
            }
        });
    }

    private void handleFire(Context appContext, int alarmId, long fireAt) {
        TongxiaoDb db = TongxiaoDb.get(appContext);
        AlarmRepository repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(appContext));

        Alarm alarm = db.alarmDao().getById(alarmId);
        if (alarm == null) {
            Log.w(TAG, "alarm " + alarmId + " no longer exists");
            return;
        }

        boolean shouldFire = resolver.shouldFire(alarm, LocalDate.now());
        if (!shouldFire) {
            RingActions actions = new RingActions(
                    db, repository, new AlarmScheduler(appContext, repository, resolver));
            actions.onSkipped(alarm, fireAt, "rule: holiday/not-today");
            return;
        }

        startRing(appContext, alarmId);
    }

    private static void startRing(Context appContext, int alarmId) {
        Intent ring = new Intent(appContext, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, alarmId);
        ContextCompat.startForegroundService(appContext, ring);
    }
}
