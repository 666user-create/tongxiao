package com.course.tongxiao.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.data.entity.WeatherCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

/**
 * Step 1.2 G2: every DAO gets insert/query/update coverage on an in-memory DB.
 * LiveData variants are exercised in Step 3.1 UI tests; here we assert through
 * the synchronous twins so no extra test-rule dependency is needed.
 * allowMainThread() is test-only convenience, never used in app code.
 */
@RunWith(AndroidJUnit4.class)
public class TongxiaoDbTest {

    private TongxiaoDb db;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class)
                .allowMainThreadQueries() // test-only; app code never does this
                .build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static Alarm alarm(int hour, int minute) {
        Alarm a = new Alarm();
        a.hour = hour;
        a.minute = minute;
        return a;
    }

    // ---- AlarmDao ----

    @Test
    public void alarm_insertUpdateDelete_andEnabledFilter() {
        long id = db.alarmDao().insert(alarm(7, 30));
        assertTrue("autoGenerate id > 0", id > 0);

        Alarm other = alarm(6, 40);
        other.enabled = false;
        db.alarmDao().insert(other);

        List<Alarm> all = db.alarmDao().getAll();
        assertEquals(2, all.size());
        assertEquals(1, db.alarmDao().getEnabled().size());

        Alarm fetched = db.alarmDao().getById((int) id);
        assertNotNull(fetched);
        assertEquals(7, fetched.hour);
        assertEquals(Alarm.RepeatMode.WORKDAY_SMART, fetched.repeatMode);
        assertTrue("defaults: vibrate on", fetched.vibrate);
        assertEquals("defaults: snooze 10", 10, fetched.snoozeMin);

        fetched.hour = 8;
        fetched.enabled = false;
        db.alarmDao().update(fetched);
        assertEquals(8, db.alarmDao().getById((int) id).hour);
        assertEquals(0, db.alarmDao().getEnabled().size());

        db.alarmDao().delete(fetched);
        assertEquals(1, db.alarmDao().getAll().size());
    }

    // ---- AlarmEventDao ----

    @Test
    public void alarmEvent_recentWindowFiltering() {
        AlarmEvent e1 = new AlarmEvent();
        e1.alarmId = 1;
        e1.fireAt = 1_000;
        e1.type = AlarmEvent.Type.FIRED;
        db.alarmEventDao().insert(e1);

        AlarmEvent e2 = new AlarmEvent();
        e2.alarmId = 1;
        e2.fireAt = 5_000;
        e2.type = AlarmEvent.Type.SNOOZE;
        e2.meta = "{\"snoozeCount\":1}";
        db.alarmEventDao().insert(e2);

        AlarmEvent e3 = new AlarmEvent();
        e3.alarmId = 2;
        e3.fireAt = 5_000;
        db.alarmEventDao().insert(e3);

        List<AlarmEvent> recent = db.alarmEventDao().getRecent(1, 2_000);
        assertEquals("only alarm1 after window start", 1, recent.size());
        assertEquals(AlarmEvent.Type.SNOOZE, recent.get(0).type);
        assertEquals(3, db.alarmEventDao().getSince(0).size());
    }

    // ---- GroupMemberDao ----

    @Test
    public void groupMember_upsertAndOnlineFlag() {
        GroupMember m = new GroupMember();
        m.deviceId = "dev-A";
        m.nick = "小李";
        m.groupKey = "123456";
        m.grantedMask = GroupMember.PERM_MUTE | GroupMember.PERM_SEE;
        m.joinAt = 100;
        db.groupMemberDao().upsert(m);

        m.nick = "小李子";
        db.groupMemberDao().upsert(m); // REPLACE, no duplicate row
        assertEquals(1, db.groupMemberDao().getAll().size());

        GroupMember got = db.groupMemberDao().getByDeviceId("dev-A");
        assertEquals("小李子", got.nick);
        assertEquals(3, got.grantedMask);

        db.groupMemberDao().setOnline("dev-A", true, 999);
        got = db.groupMemberDao().getByDeviceId("dev-A");
        assertTrue(got.online);
        assertEquals(999, got.lastSeenAt);
    }

    // ---- RemoteAlarmDao ----

    @Test
    public void remoteAlarm_replaceOwnerFullSync() {
        RemoteAlarm r1 = new RemoteAlarm();
        r1.ownerDeviceId = "dev-B";
        r1.srcAlarmId = 42;
        r1.alarmLabel = "旧数据";
        r1.ringingNow = true;
        db.remoteAlarmDao().upsertAll(Collections.singletonList(r1));
        assertEquals(1, db.remoteAlarmDao().getRinging().size());

        RemoteAlarm fresh = new RemoteAlarm();
        fresh.ownerDeviceId = "dev-B";
        fresh.srcAlarmId = 43;
        fresh.alarmLabel = "早八";
        db.remoteAlarmDao().replaceOwner("dev-B", Collections.singletonList(fresh));

        assertEquals("sync replaces snapshot", 1, db.remoteAlarmDao().getAll("dev-B").size());
        assertEquals("old ringing row gone after full replace",
                0, db.remoteAlarmDao().getRinging().size());
    }

    // ---- PendingRequestDao ----

    @Test
    public void pendingRequest_ignoreDuplicate_andExpireSweep() {
        PendingRequest p = new PendingRequest();
        p.reqId = "req-1";
        p.fromDeviceId = "dev-C";
        p.type = PendingRequest.Kind.SET_CHALLENGE;
        p.payloadJson = "{\"questions\":2}";
        p.createdAt = 1_000;
        p.expireAt = 2_000;
        assertTrue(db.pendingRequestDao().insert(p) > 0);

        PendingRequest dup = new PendingRequest();
        dup.reqId = "req-1";
        dup.type = PendingRequest.Kind.ADD_ALARM;
        assertEquals("IGNORE on conflict", -1, db.pendingRequestDao().insert(dup));
        assertEquals(1, db.pendingRequestDao().getPending().size());

        db.pendingRequestDao().setStatus("req-1", PendingRequest.Status.ACCEPTED);
        assertEquals(0, db.pendingRequestDao().getPending().size());

        // Re-insert as pending with future expiry, then sweep with now > expireAt.
        PendingRequest p2 = new PendingRequest();
        p2.reqId = "req-2";
        p2.expireAt = 1_500;
        db.pendingRequestDao().insert(p2);
        assertEquals(1, db.pendingRequestDao().expireOverdue(2_000));
        assertEquals(0, db.pendingRequestDao().getPending().size());
    }

    // ---- WeatherCacheDao ----

    @Test
    public void weatherCache_upsertGetClear() {
        WeatherCache w = new WeatherCache();
        w.city = "北京";
        w.date = "2026-10-28";
        w.condition = "rain";
        w.minTemp = 8;
        w.fetchedAt = 123;
        db.weatherCacheDao().upsert(w);

        WeatherCache got = db.weatherCacheDao().get("北京", "2026-10-28");
        assertNotNull(got);
        assertEquals("rain", got.condition);

        assertNull(db.weatherCacheDao().get("北京", "2026-10-29"));

        w.condition = "snow";
        db.weatherCacheDao().upsert(w);
        assertEquals("snow", db.weatherCacheDao().get("北京", "2026-10-28").condition);

        db.weatherCacheDao().clearAll();
        assertNull(db.weatherCacheDao().get("北京", "2026-10-28"));
    }
}
