package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Fire/behavior log feeding AdaptiveEngine, stats screen and op audit (design.md §3). */
@Entity(tableName = "alarm_events",
        indices = @Index(value = {"alarmId", "fireAt"}))
public class AlarmEvent {

    public enum Type {
        FIRED, SKIPPED_HOLIDAY, SKIPPED_REASON, SNOOZE, STOPPED,
        EMERGENCY_STOP, CHALLENGE_FAIL
    }

    @PrimaryKey(autoGenerate = true)
    public long id;

    public int alarmId;
    public long fireAt;
    public long stopAt;

    @NonNull
    public Type type = Type.FIRED;

    /** JSON: snooze count, challenge duration/errors, weather reason, remote op source. */
    public String meta;
}
