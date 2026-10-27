package com.course.tongxiao.data.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;

import java.util.List;

/**
 * Single entry point for alarm reads/writes (design.md §3.3).
 *
 * <p>Threading contract: every method here is BLOCKING and must be called from a
 * background thread. The UI layer (ViewModel, Step 3.1) owns the executor;
 * reads for the list screen use {@link #observeAll()} which Room dispatches on
 * its query executor automatically.
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

    public LiveData<List<Alarm>> observeAll() {
        return db.alarmDao().observeAll();
    }

    /** @return new rowId (Room does not back-fill {@code alarm.id}). */
    public long insert(Alarm alarm) {
        return db.alarmDao().insert(alarm);
    }

    /** Full-row update; pass an entity read back from DB. @return rows affected. */
    public int update(Alarm alarm) {
        return db.alarmDao().update(alarm);
    }

    /** Targeted switch toggle used by the list screen. @return rows affected. */
    public int setEnabled(int id, boolean enabled) {
        return db.alarmDao().setEnabled(id, enabled);
    }

    /**
     * Deletes the alarm and its trigger history atomically (§3.3).
     *
     * @return rows affected for the alarm row (0 = already gone).
     */
    public int deleteWithEvents(Alarm alarm) {
        return db.runInTransaction(() -> {
            db.alarmEventDao().deleteByAlarm(alarm.id);
            return db.alarmDao().delete(alarm);
        });
    }

    /** Snapshot for AlarmScheduler.rescheduleAll (Step 2.2/2.4). */
    public List<Alarm> getEnabled() {
        return db.alarmDao().getEnabled();
    }

    /** All alarms as a plain list (debug/self-check paths, Step 6.4). */
    public List<Alarm> getAll() {
        return db.alarmDao().getAll();
    }
}
