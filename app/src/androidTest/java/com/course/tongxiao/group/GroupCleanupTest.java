package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * design.md §3.3: logical foreign keys are compensated by transactional cleanup —
 * leaving must not leave orphaned mirror rows or stale confirmations behind.
 */
@RunWith(AndroidJUnit4.class)
public class GroupCleanupTest {

    private static final String MEMBER_A = "member-a";
    private static final String MEMBER_B = "member-b";

    private TongxiaoDb db;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), TongxiaoDb.class).build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void removeMember_clearsMirrorAndPendingRequestsForThatSenderOnly() {
        seedMember(MEMBER_A);
        seedMember(MEMBER_B);
        seedMirror(MEMBER_A, "uid-1");
        seedMirror(MEMBER_B, "uid-2");
        seedPending("req-a", MEMBER_A);
        seedPending("req-b", MEMBER_B);

        assertTrue(GroupCleanup.removeMember(db, MEMBER_A));

        assertNull("member row is gone", db.groupMemberDao().getByDeviceId(MEMBER_A));
        assertTrue("mirror of the departed member is gone",
                db.remoteAlarmDao().getAll(MEMBER_A).isEmpty());
        assertNull("their pending request is gone",
                db.pendingRequestDao().getByReqId("req-a"));

        assertNotNull("the other member must survive", db.groupMemberDao().getByDeviceId(MEMBER_B));
        assertFalse(db.remoteAlarmDao().getAll(MEMBER_B).isEmpty());
        assertNotNull(db.pendingRequestDao().getByReqId("req-b"));
    }

    @Test
    public void removeMember_unknownDevice_isANoOp() {
        assertFalse(GroupCleanup.removeMember(db, "nobody"));
    }

    @Test
    public void removeAllMembers_clearsEverythingOnLeaving() {
        seedMember(MEMBER_A);
        seedMember(MEMBER_B);
        seedMirror(MEMBER_A, "uid-1");
        seedMirror(MEMBER_B, "uid-2");
        seedPending("req-a", MEMBER_A);
        seedPending("req-b", MEMBER_B);

        assertEquals(2, GroupCleanup.removeAllMembers(db));

        assertTrue(db.groupMemberDao().getAll().isEmpty());
        assertTrue(db.remoteAlarmDao().getAll(MEMBER_A).isEmpty());
        assertTrue(db.remoteAlarmDao().getAll(MEMBER_B).isEmpty());
        assertEquals(0, db.pendingRequestDao().getByStatus(PendingRequest.Status.PENDING).size());
    }

    @Test
    public void removeAllMembers_withNoMembers_isANoOp() {
        assertEquals(0, GroupCleanup.removeAllMembers(db));
    }

    private void seedMember(String deviceId) {
        GroupMember member = new GroupMember();
        member.deviceId = deviceId;
        member.nick = deviceId;
        member.groupKey = "123456";
        member.grantedMask = GroupMember.PERM_MUTE;
        member.online = true;
        member.joinAt = System.currentTimeMillis();
        db.groupMemberDao().upsert(member);
    }

    private void seedMirror(String owner, String uid) {
        RemoteAlarm row = new RemoteAlarm();
        row.ownerDeviceId = owner;
        row.srcAlarmUid = uid;
        row.alarmLabel = "早八";
        row.timeText = "06:40";
        row.repeatText = "工作日";
        row.enabled = true;
        db.remoteAlarmDao().upsertAll(java.util.Collections.singletonList(row));
    }

    private void seedPending(String reqId, String fromDeviceId) {
        PendingRequest request = new PendingRequest();
        request.reqId = reqId;
        request.fromDeviceId = fromDeviceId;
        request.type = PendingRequest.Kind.ADD_ALARM;
        request.payloadJson = "{\"hour\":6,\"minute\":40}";
        request.createdAt = System.currentTimeMillis();
        request.expireAt = System.currentTimeMillis() + 7_200_000L;
        request.status = PendingRequest.Status.PENDING;
        db.pendingRequestDao().insert(request);
    }
}