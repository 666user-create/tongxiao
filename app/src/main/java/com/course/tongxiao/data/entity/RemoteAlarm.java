package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Read-only mirror of a peer's schedule (design.md §3). srcAlarmId locates CMD_MUTE target. */
@Entity(tableName = "remote_alarms",
        primaryKeys = {"ownerDeviceId", "srcAlarmId"})
public class RemoteAlarm {

    @NonNull
    public String ownerDeviceId = "";

    /** Peer-side alarms.id, carried by SYNC_PUSH. */
    public int srcAlarmId;

    @NonNull
    public String alarmLabel = "";

    @NonNull
    public String timeText = "";

    @NonNull
    public String repeatText = "";

    public boolean enabled;
    public boolean ringingNow;
    public long syncedAt;
}
