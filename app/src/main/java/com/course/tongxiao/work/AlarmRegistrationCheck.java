package com.course.tongxiao.work;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.WorkerThread;

import com.course.tongxiao.alarm.AlarmIntents;
import com.course.tongxiao.alarm.AlarmReceiver;
import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;

import java.util.List;
import java.util.Locale;

/**
 * Step 6.4 registration self-check (design.md §6 table, "静默失败无人知"): compare
 * what the Room says should be armed with what {@code AlarmManager} actually holds,
 * and re-arm whatever went missing.
 *
 * <p>The probe is the same PendingIntent {@link AlarmScheduler} creates — same
 * component, action and requestCode ({@code alarm.id}) — queried with
 * {@code FLAG_NO_CREATE}. Extras do not participate in PendingIntent matching, so
 * a snooze-shaped registration (extra {@code from_snooze}) still counts as
 * "armed", which is what we want: the alarm will ring.
 *
 * <p>Kept separate from {@link SelfCheckWorker} so the check can be driven from a
 * test (or from a debug menu) without booting WorkManager, and so the repair step
 * is exercised against the real {@code AlarmManager}.
 */
public final class AlarmRegistrationCheck {

    private static final String TAG = "AlarmSelfCheck";

    private AlarmRegistrationCheck() {
    }

    /** What one sweep found and fixed. Logged as one line, asserted by tests. */
    public static final class Report {

        /** Enabled alarms in Room: the expected set. */
        public final int enabledAlarms;
        /** Of those, how many already had a live PendingIntent registration. */
        public final int registered;
        /** Of those, how many had to be re-armed in this sweep. */
        public final int repaired;

        Report(int enabledAlarms, int registered, int repaired) {
            this.enabledAlarms = enabledAlarms;
            this.registered = registered;
            this.repaired = repaired;
        }

        /** Registrations that were missing when the sweep started. */
        public int missing() {
            return enabledAlarms - registered;
        }

        @Override
        public String toString() {
            return "enabled=" + enabledAlarms + " registered=" + registered
                    + " missing=" + missing() + " repaired=" + repaired;
        }
    }

    /**
     * One full sweep: probe every enabled alarm, re-arm the missing ones.
     *
     * <p>Per-alarm isolation on purpose ("宁响勿哑"): one row that refuses to
     * schedule must not abort the sweep and leave the other alarms unarmed.
     *
     * @param repository supplies the expected set (Room)
     * @param scheduler  performs the re-arm ({@link AlarmScheduler#schedule})
     */
    @WorkerThread
    public static Report run(Context context, AlarmRepository repository, AlarmScheduler scheduler) {
        List<Alarm> enabled = repository.getEnabled();
        int registered = 0;
        int repaired = 0;
        for (Alarm alarm : enabled) {
            if (isRegistered(context, alarm)) {
                registered++;
                continue;
            }
            Log.i(TAG, "missing registration for alarm " + alarm.id + " ("
                    + String.format(Locale.ROOT, "%d:%02d", alarm.hour, alarm.minute)
                    + "), re-arming");
            try {
                if (scheduler.schedule(alarm) > 0) {
                    repaired++;
                }
            } catch (RuntimeException one) {
                Log.e(TAG, "re-arm failed for alarm " + alarm.id, one);
            }
        }
        return new Report(enabled.size(), registered, repaired);
    }

    /** True when the system still holds this alarm's trigger registration. */
    @AnyThread
    public static boolean isRegistered(Context context, Alarm alarm) {
        return probe(context, alarm) != null;
    }

    /**
     * {@code FLAG_NO_CREATE}: ask for the existing record only, never create one —
     * probing must not be able to make an alarm look armed.
     */
    private static PendingIntent probe(Context context, Alarm alarm) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        return PendingIntent.getBroadcast(context, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
    }
}
