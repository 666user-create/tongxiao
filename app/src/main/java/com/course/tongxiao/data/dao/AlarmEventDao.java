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

    /** All events in a window for stats screen (Step 6.3). Full scan by design: window is ≤ weeks. */
    @Query("SELECT * FROM alarm_events WHERE fireAt >= :since ORDER BY fireAt")
    List<AlarmEvent> getSince(long since);

    /** Called by AlarmRepository.deleteWithEvents (design.md §3.3), same transaction. */
    @Query("DELETE FROM alarm_events WHERE alarmId = :alarmId")
    int deleteByAlarm(int alarmId);
}
