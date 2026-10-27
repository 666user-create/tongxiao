package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Dorm-group member (design.md §3). grantedMask = permissions I grant to this peer. */
@Entity(tableName = "group_members")
public class GroupMember {

    /** bit0: may mute my ringing alarms. */
    public static final int PERM_MUTE = 1;
    /** bit1: may see my schedule mirror. */
    public static final int PERM_SEE = 1 << 1;
    /** bit2: may send SET_REQUESTs (add/remove alarm, edit challenge). */
    public static final int PERM_SET = 1 << 2;

    @PrimaryKey
    @NonNull
    public String deviceId = "";

    @NonNull
    public String nick = "";

    @NonNull
    public String groupKey = "";

    public int grantedMask;

    public boolean online;
    public long lastSeenAt;
    public long joinAt;
}
