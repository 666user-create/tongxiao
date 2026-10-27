package com.course.tongxiao.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Step 6.4 keep-alive self-check (design.md §6): every 6h verify that every
 * enabled alarm still has an {@code AlarmManager} registration and re-arm the
 * gaps, so a silently dropped alarm is noticed long before the next morning.
 *
 * <p>WorkManager is the right owner of this work because it survives process
 * death and Doze, is persisted across reboots, and does not need a foreground
 * service. The sweep itself lives in {@link AlarmRegistrationCheck} so it can be
 * tested (and run manually) without WorkManager.
 *
 * <p>Registration: {@link #schedule(Context)} registers the unique periodic work
 * with {@link ExistingPeriodicWorkPolicy#KEEP} — a second app start must not
 * reschedule (and thereby postpone) the existing chain.
 */
public class SelfCheckWorker extends Worker {

    private static final String TAG = "SelfCheckWorker";

    /** Unique work names: WorkManager dedupes on these. */
    public static final String UNIQUE_PERIODIC_WORK = "tongxiao_alarm_self_check";
    public static final String UNIQUE_ONE_SHOT_WORK = "tongxiao_alarm_self_check_once";

    /** design.md §6: "每 6h WorkManager 自检". */
    public static final long PERIOD_HOURS = 6;

    public SelfCheckWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context appContext = getApplicationContext();
        try {
            checkNow(appContext);
            return Result.success();
        } catch (RuntimeException e) {
            // Transient (DB locked, ROM refusing to schedule): retry with
            // WorkManager's backoff instead of waiting a whole 6h period.
            Log.e(TAG, "self-check failed", e);
            return Result.retry();
        }
    }

    /**
     * Builds the production dependencies and runs one sweep. Blocking: callers
     * must be on a worker thread (Worker.doWork already is; runOnce() uses
     * WorkManager so it inherits that).
     */
    @WorkerThread
    public static AlarmRegistrationCheck.Report checkNow(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmRepository repository = AlarmRepository.create(appContext);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(appContext));
        AlarmScheduler scheduler = new AlarmScheduler(appContext, repository, resolver);
        return checkNow(appContext, repository, scheduler);
    }

    /** Same sweep with explicit dependencies (tests, or a debug trigger). */
    @WorkerThread
    public static AlarmRegistrationCheck.Report checkNow(Context context,
                                                         AlarmRepository repository,
                                                         AlarmScheduler scheduler) {
        AlarmRegistrationCheck.Report report =
                AlarmRegistrationCheck.run(context, repository, scheduler);
        Log.i(TAG, "self-check " + report);
        return report;
    }

    /**
     * Registers the 6h periodic self-check. Idempotent: KEEP leaves an already
     * scheduled chain alone, so calling this on every app start (and after every
     * reboot, WorkManager persists the work itself) cannot stack duplicates.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(SelfCheckWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                        .build();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_WORK,
                        ExistingPeriodicWorkPolicy.KEEP, request);
        Log.i(TAG, String.format(Locale.ROOT,
                "periodic self-check registered (%dh, KEEP)", PERIOD_HOURS));
    }

    /**
     * One sweep right now (app start path). Runs through WorkManager rather than
     * inline so the caller (Activity/Application, main thread) never touches Room.
     */
    public static void runOnce(Context context) {
        OneTimeWorkRequest request = OneTimeWorkRequest.from(SelfCheckWorker.class);
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request);
        Log.i(TAG, "one-shot self-check enqueued");
    }
}
