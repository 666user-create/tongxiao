package com.course.tongxiao.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Step 1.2 G2: every DAO gets insert/query/update coverage on an in-memory DB.
 * LiveData variants are exercised in Step 3.1 UI tests; here we assert through
 * the synchronous twins so no extra test-rule dependency is needed.
 * allowMainThreadQueries() is test-only convenience, never used in app code.
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
    public void alarm_insertUpdateDeleteAndEnabledFilter() {
        Alarm a = alarm(7, 30);
        a.label = "早八";
        long id = db.alarmDao().insert(a);
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
        assertEquals("早八", fetched.label);
        assertEquals(Alarm.RepeatMode.WORKDAY_SMART, fetched.repeatMode);
        assertTrue(fetched.vibrate);
        assertEquals(10, fetched.snoozeMin);

        // uid round-trip + getByUid lookup (cross-device identity, design.md §3)
        assertNotNull(fetched.uid);
        assertTrue(fetched.uid.length() > 10);
        assertEquals(fetched.id, db.alarmDao().getByUid(fetched.uid).id);

        fetched.hour = 8;
        fetched.enabled = false;
        assertEquals(1, db.alarmDao().update(fetched));
        assertEquals(8, db.alarmDao().getById((int) id).hour);
        assertEquals(0, db.alarmDao().getEnabled().size());

        assertEquals(1, db.alarmDao().delete(fetched));
        assertEquals(1, db.alarmDao().getAll().size());
        assertNull("getById on missing row returns null, not crash",
                db.alarmDao().getById(9999));
    }

    @Test
    public void alarm_objectDefaultsAndUidUniqueness() {
        // Object-level defaults (initializer semantics; DB round-trip can't prove this).
        Alarm fresh = new Alarm();
        assertEquals(10, fresh.snoozeMin);
        assertTrue(fresh.skipHolidays);
        assertTrue(fresh.enabled);
        assertTrue(fresh.vibrate);
        assertEquals(Alarm.RepeatMode.WORKDAY_SMART, fresh.repeatMode);
        assertEquals("", fresh.label);
        assertNotNull(fresh.uid);

        // Two new objects must get distinct uids.
        assertTrue(!fresh.uid.equals(new Alarm().uid));
    }

    // ---- enum persistence: raw TEXT must equal enum names (plan.md Step 1.2 review point) ----

    @Test
    public void enums_storedAsStableStrings_rawSqlAsserts() throws Exception {
        Alarm a = alarm(6, 0);
        // @Insert returning long does NOT back-fill a.id — capture the rowId.
        long alarmRowId = db.alarmDao().insert(a);
        assertTrue(alarmRowId > 0);

        AlarmEvent e = new AlarmEvent();
        e.alarmId = (int) alarmRowId;
        e.fireAt = 1;
        e.type = AlarmEvent.Type.FIRED;
        db.alarmEventDao().insert(e);

        PendingRequest p = new PendingRequest();
        p.reqId = "raw-1";
        p.type = PendingRequest.Kind.SET_CHALLENGE;
        p.createdAt = 1;
        p.expireAt = 2;
        db.pendingRequestDao().insert(p);

        // Room's public query API (openHelper is not exposed in Room 2.8 KMP build).
        Cursor c1 = db.query("SELECT repeatMode FROM alarms WHERE id = ?",
                new Object[]{alarmRowId});
        try {
            assertTrue(c1.moveToFirst());
            assertEquals("WORKDAY_SMART", c1.getString(0));
        } finally {
            c1.close();
        }

        Cursor c2 = db.query("SELECT type FROM alarm_events WHERE fireAt = 1", null);
        try {
            assertTrue(c2.moveToFirst());
            assertEquals("FIRED", c2.getString(0));
        } finally {
            c2.close();
        }

        Cursor c3 = db.query("SELECT type, status FROM pending_requests WHERE reqId = 'raw-1'", null);
        try {
            assertTrue(c3.moveToFirst());
            assertEquals("SET_CHALLENGE", c3.getString(0));
            assertEquals("PENDING", c3.getString(1));
        } finally {
            c3.close();
        }
    }

    // ---- AlarmEventDao ----

    @Test
    public void alarmEvent_recentWindowAndCascadeDelete() {
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

        // Repository cascade support (design.md §3.3): delete events of alarm 1 only.
        assertEquals(2, db.alarmEventDao().deleteByAlarm(1));
        assertEquals(1, db.alarmEventDao().getSince(0).size());
    }

    // ---- GroupMemberDao ----

    @Test
    public void groupMember_upsertAndTargetedUpdates() {
        GroupMember m = new GroupMember();
        m.deviceId = "dev-A";
        m.nick = "小李";
        m.groupKey = "123456";
        m.grantedMask = GroupMember.PERM_MUTE | GroupMember.PERM_SEE;
        m.joinAt = 100;
        db.groupMemberDao().upsert(m);

        m.nick = "小李子";
        db.groupMemberDao().upsert(m); // full-row REPLACE, same object read back
        assertEquals(1, db.groupMemberDao().getAll().size());
        assertEquals("小李子", db.groupMemberDao().getByDeviceId("dev-A").nick);

        // Targeted edits must NOT clobber untouched columns (review #3).
        db.groupMemberDao().setOnline("dev-A", true, 999);
        db.groupMemberDao().updateProfile("dev-A", "小李四", "654321");
        GroupMember got = db.groupMemberDao().getByDeviceId("dev-A");
        assertEquals("小李四", got.nick);
        assertEquals("654321", got.groupKey);
        assertEquals("joinAt survived", 100, got.joinAt);
        assertTrue("online survived", got.online);
        assertEquals("lastSeenAt survived", 999, got.lastSeenAt);
        assertEquals("mask survived", 3, got.grantedMask);

        // Total switch (design.md §3.2).
        db.groupMemberDao().setGrantedMask("dev-A", 0);
        assertEquals(0, db.groupMemberDao().getByDeviceId("dev-A").grantedMask);
    }

    // ---- RemoteAlarmDao ----

    private static RemoteAlarm remote(String owner, String uid, String label, boolean ringing) {
        RemoteAlarm r = new RemoteAlarm();
        r.ownerDeviceId = owner;
        r.srcAlarmUid = uid;
        r.alarmLabel = label;
        r.ringingNow = ringing;
        r.syncedAt = 1;
        return r;
    }

    @Test
    public void remoteAlarm_replaceOwnerIsScopedAndForcesOwnerTag() {
        // Two owners, both with a ringing row.
        db.remoteAlarmDao().replaceOwner("dev-A",
                Collections.singletonList(remote("dev-A", "u-a1", "A早八", true)));
        db.remoteAlarmDao().replaceOwner("dev-B",
                Collections.singletonList(remote("dev-B", "u-b1", "B夜跑", true)));
        assertEquals(2, db.remoteAlarmDao().getRinging().size());

        // Replace B with a payload that smuggles a dev-A-owned row: the row must
        // be re-tagged to dev-B, never touch dev-A's mirror (review #4).
        RemoteAlarm smuggled = remote("dev-A", "u-x", "污染行", false);
        db.remoteAlarmDao().replaceOwner("dev-B",
                Arrays.asList(remote("dev-B", "u-b2", "B早八", false), smuggled));

        assertEquals("A mirror survived intact", 1, db.remoteAlarmDao().getAll("dev-A").size());
        assertEquals("u-a1 still ringing", 1, db.remoteAlarmDao().getRinging().size());
        assertEquals("B replaced", 2, db.remoteAlarmDao().getAll("dev-B").size());
        assertEquals("smuggled row re-tagged to B", "dev-B", smuggled.ownerDeviceId);
        for (RemoteAlarm r : db.remoteAlarmDao().getAll("dev-B")) {
            assertEquals("dev-B", r.ownerDeviceId);
        }
    }

    @Test
    public void remoteAlarm_emptyClearAndSyncedAtStamp() {
        db.remoteAlarmDao().replaceOwner("dev-A",
                Collections.singletonList(remote("dev-A", "u-a1", "x", true)));
        RemoteAlarm noStamp = remote("dev-A", "u-a2", "y", false);
        noStamp.syncedAt = 0;
        db.remoteAlarmDao().replaceOwner("dev-A", Collections.singletonList(noStamp));
        assertTrue("missing syncedAt stamped",
                db.remoteAlarmDao().getAll("dev-A").get(0).syncedAt > 0);

        db.remoteAlarmDao().replaceOwner("dev-A", new ArrayList<>());
        assertEquals("empty list clears owner", 0, db.remoteAlarmDao().getAll("dev-A").size());

        db.remoteAlarmDao().replaceOwner("dev-A", null); // null-safe
        assertEquals(0, db.remoteAlarmDao().getAll("dev-A").size());
    }

    // ---- PendingRequestDao ----

    @Test
    public void pendingRequest_ignoreDuplicate_statusAndExpiry() {
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
        assertEquals(1, db.pendingRequestDao().getByStatus(PendingRequest.Status.PENDING).size());

        assertEquals(1, db.pendingRequestDao().setStatus("req-1", PendingRequest.Status.ACCEPTED));
        assertEquals(0, db.pendingRequestDao().getByStatus(PendingRequest.Status.PENDING).size());

        // Re-insert as pending with future expiry, then sweep with now > expireAt.
        PendingRequest p2 = new PendingRequest();
        p2.reqId = "req-2";
        p2.expireAt = 1_500;
        db.pendingRequestDao().insert(p2);
        assertEquals(1, db.pendingRequestDao().expireOverdue(
                PendingRequest.Status.PENDING, PendingRequest.Status.EXPIRED, 2_000));
        assertEquals(0, db.pendingRequestDao().getByStatus(PendingRequest.Status.PENDING).size());
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

        // PK=city: one row per city; stale date must miss → caller degrades (design.md §5.3).
        assertNull(db.weatherCacheDao().get("北京", "2026-10-29"));

        w.condition = "snow";
        db.weatherCacheDao().upsert(w);
        assertEquals("snow", db.weatherCacheDao().get("北京", "2026-10-28").condition);

        db.weatherCacheDao().clearAll();
        assertNull(db.weatherCacheDao().get("北京", "2026-10-28"));
    }
}
