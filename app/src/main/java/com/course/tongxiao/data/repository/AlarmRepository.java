package com.course.tongxiao.data.repository;

import android.content.Context;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;

import java.util.List;

/**
 * Single entry point for alarm reads/writes (design.md §3.3).
 *
 * <p><b>Threading contract.</b> All DB access methods except {@link #observeAll()}
 * are blocking and annotated {@link WorkerThread}; Room throws
 * "Cannot access database on the main thread" otherwise. Who calls from where:
 * <ul>
 *   <li>ViewModel (Step 3.1) — its own ExecutorService;</li>
 *   <li>AlarmReceiver / BOOT &amp; TIME_SET receivers (Step 2.2/2.4) — {@code goAsync()}
 *       only extends the receiver's lifetime, it does NOT switch threads. Hand the
 *       work to an Executor/IntentService and call {@code finish()} there; never
 *       call into this class inline in {@code onReceive};</li>
 *   <li>WorkManager self-check (Step 6.4) — already on a background thread.</li>
 * </ul>
 * {@link #observeAll()} returns LiveData and is the only main-thread-safe entry
 * (Room dispatches the query on its own executor).
 *
 * <p>Deleting an alarm must go through {@link #deleteWithEvents(Alarm)} so the
 * alarm_events rows go away in the same transaction (logical foreign key, §3.3).
 */
public class AlarmRepository {

    private final TongxiaoDb db;

    public AlarmRepository(TongxiaoDb db) {
        this.db = db;
    }

    public static AlarmRepository create(Context appContext) {
        return new AlarmRepository(TongxiaoDb.get(appContext));
    }

    /** Main-thread safe: Room observes and dispatches itself. */
    public LiveData<List<Alarm>> observeAll() {
        return db.alarmDao().observeAll();
    }

    /** @return new rowId. Room does NOT back-fill {@code alarm.id}; capture this value. */
    @WorkerThread
    public long insert(Alarm alarm) {
        return db.alarmDao().insert(alarm);
    }

    /** Full-row update; pass an entity read back from DB. @return rows affected. */
    @WorkerThread
    public int update(Alarm alarm) {
        return db.alarmDao().update(alarm);
    }

    /** Targeted switch toggle used by the list screen. @return rows affected. */
    @WorkerThread
    public int setEnabled(int id, boolean enabled) {
        return db.alarmDao().setEnabled(id, enabled);
    }

    /**
     * Deletes the alarm and its trigger history atomically (§3.3).
     *
     * <p>Requires a <em>persisted</em> entity ({@code id > 0}). Rejecting id&lt;=0
     * up front matters: the event delete is by {@code alarmId}, so an id of 0
     * would silently wipe the "alarmId=0" orphan bucket and then report 0 rows,
     * which a caller would misread as "already deleted". Deleting by id also
     * means a partially populated entity is fine (only the id is used).
     *
     * @return rows affected for the alarm row; 0 means the alarm row was already
     *         gone — its orphaned events are still cleaned up in this call.
     * @throws IllegalArgumentException if the entity was never persisted.
     */
    @WorkerThread
    public int deleteWithEvents(Alarm alarm) {
        if (alarm == null || alarm.id <= 0) {
            throw new IllegalArgumentException(
                    "deleteWithEvents requires a persisted alarm (id > 0), got "
                            + (alarm == null ? "null" : String.valueOf(alarm.id)));
        }
        final int alarmId = alarm.id;
        return db.runInTransaction(() -> {
            db.alarmEventDao().deleteByAlarm(alarmId);
            // Delete by the captured id, not by the (mutable) entity, so events
            // and row can never target different alarms under a concurrent edit.
            return db.alarmDao().deleteById(alarmId);
        });
    }

    /** Snapshot for AlarmScheduler.rescheduleAll (Step 2.2/2.4). */
    @WorkerThread
    public List<Alarm> getEnabled() {
        return db.alarmDao().getEnabled();
    }

    /**
     * All alarms including disabled ones. Needed by AlarmScheduler.rescheduleAll
     * (Step 2.2) to cancel stale PendingIntent registrations: a disabled alarm
     * must also have its previously registered alarm cancelled.
     */
    @WorkerThread
    public List<Alarm> getAll() {
        return db.alarmDao().getAll();
    }
}
