package com.course.tongxiao.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
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

/** Single database (design.md §3). */
@Database(
        entities = {
                Alarm.class, AlarmEvent.class, GroupMember.class,
                RemoteAlarm.class, PendingRequest.class, WeatherCache.class
        },
        version = 2,
        exportSchema = true)
public abstract class TongxiaoDb extends RoomDatabase {

    private static volatile TongxiaoDb instance;

    /**
     * v1 → v2: per-alarm vibration strength (design.md §5.5).
     *
     * <p>A real migration instead of dropping the file: alarms are the user's data, and
     * a schema bump must not wipe them. Existing rows get "medium" — the behaviour they
     * had before the column existed.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE alarms ADD COLUMN vibrateStrength INTEGER NOT NULL DEFAULT 1");
        }
    };

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
                            .addMigrations(MIGRATION_1_2)
                            // Last resort only: without this a downgrade/unknown schema
                            // would crash the app at startup instead of resetting.
                            .fallbackToDestructiveMigration(true)
                            .build();
                }
            }
        }
        return instance;
    }
}
