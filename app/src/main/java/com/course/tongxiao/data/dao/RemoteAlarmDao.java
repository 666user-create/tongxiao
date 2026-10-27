package com.course.tongxiao.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.course.tongxiao.data.entity.RemoteAlarm;

import java.util.List;

@Dao
public interface RemoteAlarmDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<RemoteAlarm> rows);

    /**
     * SYNC_PUSH semantics: full replace of one owner's mirror (design.md §3).
     * Every incoming row is force-tagged with ownerDeviceId (never trust the
     * payload's own owner field — a stray row must not pollute another owner),
     * and null/empty is a legal "peer deleted all alarms" clear.
     */
    @Transaction
    default void replaceOwner(String ownerDeviceId, List<RemoteAlarm> rows) {
        clearOwner(ownerDeviceId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (RemoteAlarm r : rows) {
            r.ownerDeviceId = ownerDeviceId;
            if (r.syncedAt == 0) {
                r.syncedAt = System.currentTimeMillis();
            }
        }
        upsertAll(rows);
    }

    @Query("DELETE FROM remote_alarms WHERE ownerDeviceId = :ownerDeviceId")
    void clearOwner(String ownerDeviceId);

    @Query("SELECT * FROM remote_alarms WHERE ownerDeviceId = :ownerDeviceId ORDER BY timeText")
    LiveData<List<RemoteAlarm>> observeOwner(String ownerDeviceId);

    @Query("SELECT * FROM remote_alarms WHERE ownerDeviceId = :ownerDeviceId ORDER BY timeText")
    List<RemoteAlarm> getAll(String ownerDeviceId);

    @Query("SELECT * FROM remote_alarms WHERE ringingNow = 1")
    List<RemoteAlarm> getRinging();
}
