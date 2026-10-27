package com.course.tongxiao.data.dao;

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

    @Update
    void update(Alarm alarm);

    @Delete
    void delete(Alarm alarm);

    /** Live list for the main screen (Step 3.1). */
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    LiveData<List<Alarm>> observeAll();

    /** Snapshot for rescheduleAll (Step 2.2/2.4). */
    @Query("SELECT * FROM alarms")
    List<Alarm> getAll();

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    List<Alarm> getEnabled();

    @Query("SELECT * FROM alarms WHERE id = :id")
    Alarm getById(int id);
}
