package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Alarm body, one row per user alarm (design.md §3). */
@Entity(tableName = "alarms")
public class Alarm {

    public enum RepeatMode { ONCE, DAILY, CUSTOM, WORKDAY_SMART }

    @PrimaryKey(autoGenerate = true)
    public int id;

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
