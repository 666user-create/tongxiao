package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

/** Alarm body, one row per user alarm (design.md §3). */
@Entity(tableName = "alarms",
        indices = @Index(value = "uid", unique = true))
public class Alarm {

    public enum RepeatMode { ONCE, DAILY, CUSTOM, WORKDAY_SMART }

    @PrimaryKey(autoGenerate = true)
    public int id;

    /**
     * Stable cross-device identifier (design.md §3/§4). Never reuse {@link #id}
     * in protocol payloads: autoincrement restarts after a destructive rebuild,
     * so an old id could hit a DIFFERENT alarm. An old uid instead simply finds
     * nothing (CMD_MUTE answers ALARM_NOT_FOUND), which cannot mute the wrong
     * one. Unique index guards against copy/paste duplicating a uid.
     */
    @NonNull
    public String uid = UUID.randomUUID().toString();

    /** Human name shown in list/ring screens and SYNC_PUSH ("早八"). */
    @NonNull
    public String label = "";

    public int hour;
    public int minute;

    /** bit0=Mon ... bit6=Sun */
    public int dayMask;

    @NonNull
    public RepeatMode repeatMode = RepeatMode.WORKDAY_SMART;

    /** Skip statutory holidays (WORKDAY_SMART/CUSTOM only). */
    public boolean skipHolidays = true;

    public String ringtoneUri;
    public boolean vibrate = true;
    public int snoozeMin = 10;

    /** null = inherit global default challenge config (design.md §3). */
    public String challengeOverrideJson;

    /** Weather-based advance, capped by advanceMaxMin. */
    public boolean advanceEnabled;
    public int advanceMaxMin;

    public boolean enabled = true;

    /** Push into group schedule mirror when members hold bit1 of grantedMask. */
    public boolean sharedToGroup;
}
