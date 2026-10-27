package com.course.tongxiao.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.course.tongxiao.data.entity.Alarm;

import java.util.List;

@Dao
public interface AlarmDao {

    @Insert
    long insert(Alarm alarm);

    /** @return rows affected (0 = row vanished concurrently; caller must not assume success). */
    @Update
    int update(Alarm alarm);

    /**
     * @return rows affected. Does NOT touch alarm_events — deleting an alarm
     * must go through AlarmRepository.deleteWithEvents (design.md §3.3).
     */
    @Delete
    int delete(Alarm alarm);

    /** Live list for the main screen (Step 3.1). */
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    LiveData<List<Alarm>> observeAll();

    /** Snapshot for rescheduleAll (Step 2.2/2.4). */
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    List<Alarm> getAll();

    @Query("SELECT * FROM alarms WHERE enabled = 1 ORDER BY hour, minute")
    List<Alarm> getEnabled();

    @Nullable
    @Query("SELECT * FROM alarms WHERE id = :id")
    Alarm getById(int id);

    @Nullable
    @Query("SELECT * FROM alarms WHERE uid = :uid")
    Alarm getByUid(String uid);
}
