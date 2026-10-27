package com.course.tongxiao.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.course.tongxiao.data.dao.AlarmDao;
import com.course.tongxiao.data.dao.AlarmEventDao;
import com.course.tongxiao.data.dao.GroupMemberDao;
import com.course.tongxiao.data.dao.PendingRequestDao;
import com.course.tongxiao.data.dao.RemoteAlarmDao;
import com.course.tongxiao.data.dao.WeatherCacheDao;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.data.entity.WeatherCache;

/** Single database (design.md §3). v1: destructive fallback, no migration chain. */
@Database(
        entities = {
                Alarm.class, AlarmEvent.class, GroupMember.class,
                RemoteAlarm.class, PendingRequest.class, WeatherCache.class
        },
        version = 1,
        exportSchema = true)
public abstract class TongxiaoDb extends RoomDatabase {

    private static volatile TongxiaoDb instance;

    public abstract AlarmDao alarmDao();
    public abstract AlarmEventDao alarmEventDao();
    public abstract GroupMemberDao groupMemberDao();
    public abstract RemoteAlarmDao remoteAlarmDao();
    public abstract PendingRequestDao pendingRequestDao();
    public abstract WeatherCacheDao weatherCacheDao();

    public static TongxiaoDb get(Context appContext) {
        if (instance == null) {
            synchronized (TongxiaoDb.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    appContext.getApplicationContext(),
                                    TongxiaoDb.class, "tongxiao.db")
                            .fallbackToDestructiveMigration(true)
                            .build();
                }
            }
        }
        return instance;
    }
}
