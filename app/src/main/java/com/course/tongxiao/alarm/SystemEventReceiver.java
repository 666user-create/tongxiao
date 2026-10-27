package com.course.tongxiao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restores alarm registrations after system events (design.md §6).
 *
 * <p>{@code AlarmManager} registrations do not survive a reboot, and a clock or
 * time-zone change shifts every future trigger. In both cases the fix is the
 * same: rebuild the whole schedule from Room ({@code rescheduleAll()}).
 *
 * <p>{@code onReceive} runs on the main thread while {@code rescheduleAll()} hits
 * the database, so the work goes through {@code goAsync()} + an executor.
 */
public class SystemEventReceiver extends BroadcastReceiver {

    private static final String TAG = "SystemEventReceiver";

    /** One shared executor: receivers are short-lived objects. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!relevant) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        // goAsync() is only meaningful during real delivery; a direct (test)
        // invocation returns null, so tolerate it and still do the work.
        final PendingResult pending = goAsync();
        WORKER.execute(() -> {
            try {
                TongxiaoDb db = TongxiaoDb.get(appContext);
                AlarmRepository repository = new AlarmRepository(db);
                HolidayResolver resolver =
                        new HolidayResolver(HolidayCalendar.loadFromAssets(appContext));
                int scheduled = new AlarmScheduler(appContext, repository, resolver)
                        .rescheduleAll();
                Log.i(TAG, action + ": rescheduled " + scheduled + " alarm(s)");
            } catch (RuntimeException e) {
                Log.e(TAG, action + ": reschedule failed", e);
            } finally {
                if (pending != null) {
                    pending.finish();
                }
            }
        });
    }
}
