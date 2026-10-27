package com.course.tongxiao.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.course.tongxiao.data.entity.AlarmEvent;

import java.util.List;

@Dao
public interface AlarmEventDao {

    @Insert
    long insert(AlarmEvent event);

    /** Recent window for AdaptiveEngine (Step 6.1). */
    @Query("SELECT * FROM alarm_events WHERE alarmId = :alarmId AND fireAt >= :since ORDER BY fireAt")
    List<AlarmEvent> getRecent(int alarmId, long since);

    /** All events in a window for stats screen (Step 6.3). */
    @Query("SELECT * FROM alarm_events WHERE fireAt >= :since ORDER BY fireAt")
    List<AlarmEvent> getSince(long since);
}
