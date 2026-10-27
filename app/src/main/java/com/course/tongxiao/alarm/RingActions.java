package com.course.tongxiao.alarm;

import android.util.Log;

import androidx.annotation.WorkerThread;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.data.repository.AlarmRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The state changes a ringing session causes (design.md §2.2). Kept out of the
 * Service/Activity so the rules are testable without audio or UI:
 *
 * <ul>
 *   <li>stop / mute / emergency-stop → STOPPED event; recurring alarms are
 *       re-armed immediately, a ONCE alarm disables itself;</li>
 *   <li>snooze → SNOOZE event + re-arm at now+snoozeMin (independent of the
 *       regular schedule).</li>
 * </ul>
 *
 * <p>Every method is {@link WorkerThread}: the caller (Service) owns an executor.
 */
public class RingActions {

    private static final String TAG = "RingActions";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TongxiaoDb db;
    private final AlarmRepository repository;
    private final AlarmScheduler scheduler;

    public RingActions(TongxiaoDb db, AlarmRepository repository, AlarmScheduler scheduler) {
        this.db = db;
        this.repository = repository;
        this.scheduler = scheduler;
    }

    /**
     * Normal stop (or remote mute / emergency stop: same state transition, the
     * distinction lives in the recorded {@code source}).
     *
     * @param source "user" / "remote:<deviceId>" / "emergency"
     */
    @WorkerThread
    public void onStopped(Alarm alarm, long fireAtMillis, String source) {
        record(alarm, fireAtMillis, AlarmEvent.Type.STOPPED, source);

        if (alarm.repeatMode == Alarm.RepeatMode.ONCE) {
            // One-shot: ring once, then switch itself off.
            db.alarmDao().setEnabled(alarm.id, false);
            alarm.enabled = false;
            scheduler.cancel(alarm);
        } else {
            // setAlarmClock is one-shot; re-arm the next occurrence right away.
            scheduler.schedule(alarm);
        }
    }

    /**
     * Snooze: not a new schedule, a single re-arm at now+snoozeMin. The regular
     * next occurrence is scheduled when the snoozed ring is finally stopped.
     */
    @WorkerThread
    public void onSnoozed(Alarm alarm, long fireAtMillis, int snoozeMin) {
        // Clamp defensively: imported/remote data could carry 0 or a huge value,
        // which would either ring immediately or swallow the rest of the day.
        int minutes = Math.max(1, Math.min(30, snoozeMin));
        record(alarm, fireAtMillis, AlarmEvent.Type.SNOOZE, "snooze:" + minutes);
        scheduler.scheduleAt(alarm, LocalDateTime.now().plusMinutes(minutes));
    }

    /** Holiday / rule skip while the system alarm still fired (design.md §2.2). */
    @WorkerThread
    public void onSkipped(Alarm alarm, long fireAtMillis, String reason) {
        record(alarm, fireAtMillis, AlarmEvent.Type.SKIPPED_HOLIDAY, reason);
        scheduler.schedule(alarm); // arm the following valid day
    }

    private void record(Alarm alarm, long fireAtMillis, AlarmEvent.Type type, String metaSource) {
        AlarmEvent event = new AlarmEvent();
        event.alarmId = alarm.id;
        event.fireAt = fireAtMillis > 0 ? fireAtMillis : System.currentTimeMillis();
        event.stopAt = System.currentTimeMillis();
        event.type = type;
        event.meta = "{\"source\":\"" + (metaSource == null ? "" : metaSource) + "\"}";
        try {
            db.alarmEventDao().insert(event);
        } catch (RuntimeException e) {
            // Logging must never break the ring/stop path.
            Log.e(TAG, "failed to record event " + type + " for alarm " + alarm.id, e);
        }
    }
}
