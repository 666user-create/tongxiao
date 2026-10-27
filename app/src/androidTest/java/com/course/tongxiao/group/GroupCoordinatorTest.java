package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step 5.3 G2: the receiver-side security boundary (design.md §3.2).
 *
 * <p>Each test asserts both the wire answer and the local state, so "denied"
 * cannot be faked by a reply without the side effect being blocked, and "allowed"
 * cannot be faked by a side effect without the ACK.
 */
@RunWith(AndroidJUnit4.class)
public class GroupCoordinatorTest {

    private static final String PEER = "peer-device-id";
    private static final String GROUP_CODE = "654321";
    private static final String ALARM_UID = "uid-early-class";

    private TongxiaoDb db;
    private SettingsStore settings;
    private GroupIdentity identity;
    private PeerStatsStore peerStats;
    private FakeGateway gateway;
    private GroupCoordinator coordinator;
    private final List<GroupMessage> replies =
            Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();

        SharedPreferences prefs = ctx.getSharedPreferences("test_coordinator", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        settings = new SettingsStore(prefs);

        SharedPreferences identityPrefs = ctx.getSharedPreferences(
                "test_coordinator_identity", Context.MODE_PRIVATE);
        identityPrefs.edit().clear().commit();
        identity = new GroupIdentity(identityPrefs);
        identity.setGroupCode(GROUP_CODE);

        SharedPreferences statsPrefs = ctx.getSharedPreferences(
                "test_coordinator_stats", Context.MODE_PRIVATE);
        statsPrefs.edit().clear().commit();
        peerStats = new PeerStatsStore(statsPrefs);

        gateway = new FakeGateway();
        coordinator = new GroupCoordinator(db, settings, identity, gateway, peerStats);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- HELLO ----

    @Test
    public void hello_enrolsMemberWithMuteAndSeeOnly() {
        assertNull(coordinator.onHello(helloFor(PEER, "小李", GROUP_CODE)));

        GroupMember member = db.groupMemberDao().getByDeviceId(PEER);
        assertNotNull("peer is now a member", member);
        assertEquals("小李", member.nick);
        assertEquals(GROUP_CODE, member.groupKey);
        assertTrue("online after handshake", member.online);
        assertEquals("mute + see granted by default, setting stays closed (§3.2)",
                GroupMember.PERM_MUTE | GroupMember.PERM_SEE, member.grantedMask);
        assertTrue(member.joinAt > 0);
    }

    @Test
    public void hello_withWrongGroupCode_isRejectedAndNotEnrolled() {
        assertEquals(GroupProtocol.REASON_GROUP_KEY_MISMATCH,
                coordinator.onHello(helloFor(PEER, "小李", "111111")));

        assertNull(db.groupMemberDao().getByDeviceId(PEER));
    }

    @Test
    public void hello_whenThisDeviceIsNotInAGroup_isRejected() {
        identity.setGroupCode("");

        assertEquals(GroupProtocol.REASON_NOT_IN_GROUP,
                coordinator.onHello(helloFor(PEER, "小李", GROUP_CODE)));

        assertNull(db.groupMemberDao().getByDeviceId(PEER));
    }

    @Test
    public void hello_fromThisSameDevice_isRejected() {
        // Our own NSD record can resolve back to us; that must not enrol ourselves.
        assertEquals(GroupProtocol.REASON_BAD_PAYLOAD,
                coordinator.onHello(helloFor(identity.deviceId(), "我", GROUP_CODE)));
    }

    // ---- CMD_MUTE ----

    @Test
    public void mute_withPermission_mutesAcksOkAndSendsOpReceipt() {
        enrolPeer();

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertEquals(1, gateway.muted.size());
        assertEquals(PEER + "/" + ALARM_UID, gateway.muted.get(0));
        GroupMessage ack = firstReplyOf(GroupMessage.Type.CMD_ACK);
        assertNotNull("an ACK is always sent", ack);
        assertTrue(ack.bool(GroupMessage.FIELD_OK, false));

        GroupMessage receipt = firstReplyOf(GroupMessage.Type.NOTIFY_OP);
        assertNotNull("the sender's op-log needs a receipt", receipt);
        assertTrue(receipt.string(GroupMessage.FIELD_WHAT, "").contains(ALARM_UID));
    }

    @Test
    public void mute_withoutMutePermission_isDeniedAndNeverExecuted() {
        enrolPeer();
        setMask(0); // I revoke everything this member may do

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertDenied(GroupProtocol.REASON_PERMISSION_DENIED);
        assertTrue("the alarm must not be muted", gateway.muted.isEmpty());
    }

    @Test
    public void mute_withOnlySeePermission_isDenied() {
        enrolPeer();
        setMask(GroupMember.PERM_SEE | GroupMember.PERM_SET);

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertDenied(GroupProtocol.REASON_PERMISSION_DENIED);
        assertTrue(gateway.muted.isEmpty());
    }

    @Test
    public void mute_forAnotherMembersAlarm_isDenied() {
        enrolPeer();

        command(GroupMessage.cmdMute("some-third-device", ALARM_UID));

        assertDenied(GroupProtocol.REASON_PERMISSION_DENIED);
        assertTrue("one peer may not mute another peer's alarm", gateway.muted.isEmpty());
    }

    @Test
    public void mute_whenMasterSwitchOff_isDeniedWithoutTouchingTheAlarm() {
        enrolPeer();
        settings.setGroupMasterSwitchOn(false);

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertDenied(GroupProtocol.REASON_MASTER_SWITCH_OFF);
        assertTrue(gateway.muted.isEmpty());
        assertEquals("the master switch must not clear granted permissions (§3.2)",
                GroupMember.PERM_MUTE | GroupMember.PERM_SEE,
                db.groupMemberDao().getByDeviceId(PEER).grantedMask);
    }

    @Test
    public void mute_withStaleHeartbeat_isDenied() {
        enrolPeer();
        // Last heartbeat was 61s ago: the 60s freshness bound is broken.
        db.groupMemberDao().setOnline(PEER, true, System.currentTimeMillis() - 61_000L);

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertDenied(GroupProtocol.REASON_STALE_LINK);
        assertTrue(gateway.muted.isEmpty());
    }

    @Test
    public void mute_withoutAlarmUid_isRejectedAsBadPayload() {
        enrolPeer();

        command(GroupMessage.cmdMute(PEER, ""));

        assertDenied(GroupProtocol.REASON_BAD_PAYLOAD);
        assertTrue(gateway.muted.isEmpty());
    }

    @Test
    public void mute_whenAlarmIsGone_passesTheGatewayReasonThrough() {
        enrolPeer();
        gateway.muteFailure = GroupProtocol.REASON_ALARM_NOT_FOUND;

        command(GroupMessage.cmdMute(PEER, ALARM_UID));

        assertDenied(GroupProtocol.REASON_ALARM_NOT_FOUND);
    }

    // ---- SYNC_PUSH ----

    @Test
    public void syncPush_replacesTheOwnerMirrorAndForcesTheOwnerId() throws Exception {
        enrolPeer();
        command(GroupMessage.syncPush(alarms(
                alarm("uid-1", "早八", "06:40", true, false),
                alarm("uid-2", "跑步", "18:00", false, false))));

        List<RemoteAlarm> mirror = db.remoteAlarmDao().getAll(PEER);
        assertEquals(2, mirror.size());
        for (RemoteAlarm row : mirror) {
            assertEquals("owner is forced by the receiver", PEER, row.ownerDeviceId);
        }
        assertTrue(firstReplyOf(GroupMessage.Type.CMD_ACK).bool(GroupMessage.FIELD_OK, false));

        // Second push with a single row is a FULL replacement, not a merge.
        command(GroupMessage.syncPush(alarms(alarm("uid-1", "早八", "06:40", true, true))));

        mirror = db.remoteAlarmDao().getAll(PEER);
        assertEquals(1, mirror.size());
        assertEquals("uid-1", mirror.get(0).srcAlarmUid);
        assertTrue("ringingNow is mirrored so the mute button enables", mirror.get(0).ringingNow);
    }

    @Test
    public void syncPush_withoutSeePermission_isDeniedAndMirrorUntouched() {
        enrolPeer();
        setMask(GroupMember.PERM_MUTE);

        command(GroupMessage.syncPush(alarms(alarm("uid-1", "早八", "06:40", true, false))));

        assertDenied(GroupProtocol.REASON_PERMISSION_DENIED);
        assertTrue(db.remoteAlarmDao().getAll(PEER).isEmpty());
    }

    @Test
    public void syncPush_skipsRowsWithoutUid() throws Exception {
        enrolPeer();

        JSONObject broken = new JSONObject().put("label", "no uid");
        command(GroupMessage.syncPush(alarms(
                alarm("uid-1", "早八", "06:40", true, false), broken)));

        List<RemoteAlarm> mirror = db.remoteAlarmDao().getAll(PEER);
        assertEquals("an unaddressable row is useless for CMD_MUTE", 1, mirror.size());
        assertEquals("uid-1", mirror.get(0).srcAlarmUid);
    }

    // ---- SET_REQUEST ----

    @Test
    public void setRequest_landsPendingFor2hWithClampedChallenge() throws Exception {
        enrolPeer();
        setMask(GroupMember.PERM_MUTE | GroupMember.PERM_SET);

        JSONObject payload = new JSONObject()
                .put("alarmUid", "")
                .put("challenge", new JSONObject()
                        .put("mode", "QUIZ")
                        .put("questionCount", 99)
                        .put("delaySeconds", 999)
                        .put("shakeCount", 1)
                        .put("types", new JSONArray().put("ARITHMETIC"))
                        .put("wrongPolicy", "RETRY"));
        command(GroupMessage.setRequest("req-1", "SET_CHALLENGE", payload));

        assertTrue(firstReplyOf(GroupMessage.Type.CMD_ACK).bool(GroupMessage.FIELD_OK, false));

        PendingRequest stored = db.pendingRequestDao().getByReqId("req-1");
        assertNotNull(stored);
        assertEquals(PendingRequest.Status.PENDING, stored.status);
        assertEquals(PEER, stored.fromDeviceId);
        assertEquals(PendingRequest.Kind.SET_CHALLENGE, stored.type);
        assertEquals("2h unattended lifetime (§3.1)", GroupCoordinator.PENDING_TTL_MS,
                stored.expireAt - stored.createdAt);

        JSONObject clamped = new JSONObject(stored.payloadJson)
                .getJSONObject("challenge");
        assertEquals("question count clamped to 5", 5, clamped.getInt("questionCount"));
        assertEquals("delay clamped to 30s", 30, clamped.getInt("delaySeconds"));
        assertEquals("shake count clamped to the 3..10 range", 3, clamped.getInt("shakeCount"));
    }

    @Test
    public void setRequest_addAlarmPayload_isClampedToo() throws Exception {
        enrolPeer();
        setMask(GroupMember.PERM_SET);

        StringBuilder longLabel = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longLabel.append('闹');
        }
        JSONObject payload = new JSONObject()
                .put("hour", 6)
                .put("minute", 40)
                .put("repeatMode", "DAILY")
                .put("snoozeMin", 999)
                .put("label", longLabel.toString());
        command(GroupMessage.setRequest("req-add", "ADD_ALARM", payload));

        PendingRequest stored = db.pendingRequestDao().getByReqId("req-add");
        assertNotNull(stored);
        JSONObject clamped = new JSONObject(stored.payloadJson);
        assertEquals("snooze clamped to 30", 30, clamped.getInt("snoozeMin"));
        assertEquals("label truncated", RequestPayloads.MAX_LABEL_LENGTH,
                clamped.getString("label").length());
    }

    @Test
    public void setRequest_withoutSetPermission_isDeniedAndNothingQueued() {
        enrolPeer();
        setMask(GroupMember.PERM_MUTE | GroupMember.PERM_SEE);

        command(GroupMessage.setRequest("req-2", "REMOVE_ALARM",
                json("alarmUid", "uid-1")));

        assertDenied(GroupProtocol.REASON_PERMISSION_DENIED);
        assertNull(db.pendingRequestDao().getByReqId("req-2"));
    }

    @Test
    public void setRequest_withInvalidPayload_isRejectedWithBadPayload() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);

        JSONObject payload = json("hour", 99, "minute", 40, "repeatMode", "DAILY");
        command(GroupMessage.setRequest("req-3", "ADD_ALARM", payload));

        assertDenied(GroupProtocol.REASON_BAD_PAYLOAD);
        assertNull(db.pendingRequestDao().getByReqId("req-3"));
    }

    @Test
    public void setRequest_duplicateDelivery_isIdempotent() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);
        GroupMessage request = GroupMessage.setRequest("req-4", "REMOVE_ALARM",
                json("alarmUid", "uid-1"));

        command(request);
        command(request); // TCP retransmit / re-broadcast

        assertEquals(1, db.pendingRequestDao().getByStatus(PendingRequest.Status.PENDING).size());
        long okAcks = 0;
        synchronized (replies) {
            for (GroupMessage reply : replies) {
                if (reply.type() == GroupMessage.Type.CMD_ACK
                        && reply.bool(GroupMessage.FIELD_OK, false)) {
                    okAcks++;
                }
            }
        }
        assertEquals("both deliveries are acknowledged, only one row exists", 2, okAcks);
    }

    @Test
    public void setRequest_afterResolved_isNotResurrected() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);
        GroupMessage request = GroupMessage.setRequest("req-5", "REMOVE_ALARM",
                json("alarmUid", "uid-1"));
        command(request);
        coordinator.reject("req-5");

        replies.clear();
        command(request);

        assertDenied(GroupProtocol.REASON_DUPLICATE_REQUEST);
        assertEquals(PendingRequest.Status.REJECTED,
                db.pendingRequestDao().getByReqId("req-5").status);
    }

    // ---- pending request decisions ----

    @Test
    public void accept_appliesThroughTheGatewayAndMarksAccepted() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);
        command(GroupMessage.setRequest("req-6", "ADD_ALARM",
                json("hour", 7, "minute", 30, "repeatMode", "ONCE")));

        PendingRequest accepted = coordinator.accept("req-6");

        assertNotNull(accepted);
        assertEquals(1, gateway.applied.size());
        assertEquals(PendingRequest.Status.ACCEPTED,
                db.pendingRequestDao().getByReqId("req-6").status);
        assertNull("a resolved request cannot be accepted twice", coordinator.accept("req-6"));
    }

    @Test
    public void accept_whenApplyFails_marksRejectedAndKeepsStatusHonest() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);
        command(GroupMessage.setRequest("req-7", "ADD_ALARM",
                json("hour", 7, "minute", 30, "repeatMode", "ONCE")));
        gateway.applyFailure = GroupProtocol.REASON_BAD_PAYLOAD;

        assertNull(coordinator.accept("req-7"));
        assertEquals(PendingRequest.Status.REJECTED,
                db.pendingRequestDao().getByReqId("req-7").status);
    }

    @Test
    public void reject_marksRejectedWithoutApplying() {
        enrolPeer();
        setMask(GroupMember.PERM_SET);
        command(GroupMessage.setRequest("req-8", "REMOVE_ALARM",
                json("alarmUid", "uid-1")));

        assertNotNull(coordinator.reject("req-8"));

        assertEquals(PendingRequest.Status.REJECTED,
                db.pendingRequestDao().getByReqId("req-8").status);
        assertTrue(gateway.applied.isEmpty());
    }

    @Test
    public void accept_expiredRequest_returnsNullAndMarksExpired() {
        PendingRequest overdue = new PendingRequest();
        overdue.reqId = "req-9";
        overdue.fromDeviceId = PEER;
        overdue.type = PendingRequest.Kind.REMOVE_ALARM;
        overdue.payloadJson = "{\"alarmUid\":\"uid-1\"}";
        overdue.createdAt = System.currentTimeMillis() - 3 * 60 * 60 * 1000L;
        overdue.expireAt = System.currentTimeMillis() - 60_000L;
        overdue.status = PendingRequest.Status.PENDING;
        db.pendingRequestDao().insert(overdue);

        assertNull(coordinator.accept("req-9"));

        assertEquals(PendingRequest.Status.EXPIRED,
                db.pendingRequestDao().getByReqId("req-9").status);
        assertTrue("expired requests are never applied", gateway.applied.isEmpty());
    }

    @Test
    public void expireOverdue_voidsOnlyOverduePendingRequests() {
        PendingRequest fresh = pendingRow("req-fresh", System.currentTimeMillis() + 60_000L);
        PendingRequest overdue = pendingRow("req-overdue", System.currentTimeMillis() - 1_000L);
        db.pendingRequestDao().insert(fresh);
        db.pendingRequestDao().insert(overdue);

        assertEquals(1, coordinator.expireOverdue());

        assertEquals(PendingRequest.Status.PENDING,
                db.pendingRequestDao().getByReqId("req-fresh").status);
        assertEquals(PendingRequest.Status.EXPIRED,
                db.pendingRequestDao().getByReqId("req-overdue").status);
    }

    // ---- STATS_PUSH (offline-first leaderboard) ----

    @Test
    public void statsPush_isStoredWithoutAnyPermissionBit() {
        enrolPeer(); // default grant = MUTE|SEE only, no PERM_SET
        long sentAt = System.currentTimeMillis();

        command(GroupMessage.statsPush(statsPayload(445, 12, 3, sentAt)));

        List<PeerSnapshot> stored = peerStats.all();
        assertEquals(1, stored.size());
        assertEquals(PEER, stored.get(0).deviceId);
        assertEquals("小李", stored.get(0).nick);
        assertEquals(445, stored.get(0).medianStopMinute);
        assertEquals(12, stored.get(0).snoozeCount);
        assertEquals(3, stored.get(0).mutedCount);
        assertEquals(sentAt, stored.get(0).sentAt);
        assertEquals("an accepted snapshot is not a drop", 0, coordinator.droppedStats());
    }

    @Test
    public void statsPush_whenMasterSwitchOff_isDroppedAndNotStored() {
        enrolPeer();
        settings.setGroupMasterSwitchOn(false);

        command(GroupMessage.statsPush(statsPayload(445, 12, 3, System.currentTimeMillis())));

        assertTrue("master off means no remote traffic at all", peerStats.all().isEmpty());
        assertEquals(1, coordinator.droppedStats());
    }

    @Test
    public void statsPush_withImpossibleWakeUpMinute_isDroppedWithACount() {
        enrolPeer();

        // 1500 minutes is not a clock reading: the whole row is refused.
        command(GroupMessage.statsPush(statsPayload(1500, 12, 3, System.currentTimeMillis())));

        assertTrue(peerStats.all().isEmpty());
        assertEquals(1, coordinator.droppedStats());
    }

    @Test
    public void statsPush_claimedDeviceId_isOverriddenWithTheAuthenticatedSender() throws Exception {
        enrolPeer();

        // A member tries to write a row into somebody else's name.
        JSONObject payload = statsPayload(445, 12, 3, System.currentTimeMillis());
        payload.put(GroupMessage.FIELD_STATS_DEVICE_ID, "victim-device");

        command(GroupMessage.statsPush(payload));

        List<PeerSnapshot> stored = peerStats.all();
        assertEquals(1, stored.size());
        assertEquals("the row belongs to the peer that actually sent it",
                PEER, stored.get(0).deviceId);
    }

    @Test
    public void statsPush_fromANonMember_isDropped() {
        GroupMessage push = GroupMessage.statsPush(statsPayload(445, 12, 3, System.currentTimeMillis()));

        coordinator.onCommand("stranger", push, replies::add);

        assertTrue("only group members may publish into my ranking cache",
                peerStats.all().isEmpty());
        assertEquals(1, coordinator.droppedStats());
    }

    // ---- outbound SYNC_PUSH and the master switch ----

    @Test
    public void buildSyncPush_includesOnlySharedAlarmsAndTheRingingFlag() {
        Alarm shared = insertAlarm("早八", true, true);
        insertAlarm("私事", true, false);
        gateway.ringingUid = shared.uid;

        GroupMessage push = coordinator.buildSyncPush();
        JSONArray alarms = push.array(GroupMessage.FIELD_ALARMS);

        assertNotNull(alarms);
        assertEquals("only sharedToGroup alarms are pushed", 1, alarms.length());
        JSONObject only = alarms.optJSONObject(0);
        assertEquals(shared.uid, only.optString("uid"));
        assertEquals("06:40", only.optString(GroupMessage.FIELD_ALARM_TIME));
        assertTrue(only.optBoolean(GroupMessage.FIELD_ALARM_RINGING, false));
    }

    @Test
    public void masterSwitchOff_blocksEveryRemoteOperation() {
        enrolPeer();
        setMask(GroupMember.PERM_MUTE | GroupMember.PERM_SEE | GroupMember.PERM_SET);
        settings.setGroupMasterSwitchOn(false);

        GroupMessage[] commands = {
                GroupMessage.cmdMute(PEER, ALARM_UID),
                GroupMessage.syncPush(alarms(alarm("uid-1", "早八", "06:40", true, false))),
                GroupMessage.setRequest("req-blocked", "REMOVE_ALARM",
                        json("alarmUid", "uid-1"))
        };
        for (GroupMessage message : commands) {
            replies.clear();
            command(message);
            assertDenied(GroupProtocol.REASON_MASTER_SWITCH_OFF);
        }
        assertTrue(gateway.muted.isEmpty());
        assertTrue(db.remoteAlarmDao().getAll(PEER).isEmpty());
        assertNull(db.pendingRequestDao().getByReqId("req-blocked"));
    }

    // ---- helpers ----

    private void enrolPeer() {
        assertNull(coordinator.onHello(helloFor(PEER, "小李", GROUP_CODE)));
    }

    private static GroupMessage helloFor(String deviceId, String nick, String code) {
        return GroupMessage.hello(deviceId, nick, code);
    }

    private void setMask(int mask) {
        db.groupMemberDao().setGrantedMask(PEER, mask);
    }

    private void command(GroupMessage message) {
        coordinator.onCommand(PEER, message, replies::add);
    }

    @Nullable
    private GroupMessage firstReplyOf(GroupMessage.Type type) {
        synchronized (replies) {
            for (GroupMessage reply : replies) {
                if (reply.type() == type) {
                    return reply;
                }
            }
        }
        return null;
    }

    private void assertDenied(String expectedReason) {
        GroupMessage ack = firstReplyOf(GroupMessage.Type.CMD_ACK);
        assertNotNull("denials must answer with CMD_ACK", ack);
        assertFalse("a denied command must not be acknowledged as ok", ack.bool(GroupMessage.FIELD_OK, true));
        assertEquals(expectedReason, ack.string(GroupMessage.FIELD_REASON, ""));
    }

    /** Leaderboard snapshot payload as the stats page would build it. */
    private static JSONObject statsPayload(int medianStopMinute, int snoozeCount,
                                           int mutedCount, long sentAt) {
        return json(
                GroupMessage.FIELD_STATS_DEVICE_ID, PEER,
                GroupMessage.FIELD_NICK, "小李",
                GroupMessage.FIELD_STATS_DAYS, 7,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, medianStopMinute,
                GroupMessage.FIELD_STATS_SNOOZE, snoozeCount,
                GroupMessage.FIELD_STATS_MUTED, mutedCount,
                GroupMessage.FIELD_STATS_SENT_AT, sentAt);
    }

    private static PendingRequest pendingRow(String reqId, long expireAt) {        PendingRequest request = new PendingRequest();
        request.reqId = reqId;
        request.fromDeviceId = PEER;
        request.type = PendingRequest.Kind.REMOVE_ALARM;
        request.payloadJson = "{\"alarmUid\":\"uid-1\"}";
        request.createdAt = System.currentTimeMillis();
        request.expireAt = expireAt;
        request.status = PendingRequest.Status.PENDING;
        return request;
    }

    private Alarm insertAlarm(String label, boolean enabled, boolean sharedToGroup) {
        Alarm alarm = new Alarm();
        alarm.label = label;
        alarm.hour = 6;
        alarm.minute = 40;
        alarm.enabled = enabled;
        alarm.sharedToGroup = sharedToGroup;
        alarm.repeatMode = Alarm.RepeatMode.DAILY;
        repoInsert(alarm);
        return alarm;
    }

    private void repoInsert(Alarm alarm) {
        db.alarmDao().insert(alarm);
    }

    private static JSONObject alarm(String uid, String label, String time,
                                    boolean enabled, boolean ringing) {
        try {
            return new JSONObject()
                    .put("uid", uid)
                    .put(GroupMessage.FIELD_ALARM_LABEL, label)
                    .put(GroupMessage.FIELD_ALARM_TIME, time)
                    .put(GroupMessage.FIELD_ALARM_REPEAT, "每天")
                    .put(GroupMessage.FIELD_ALARM_ENABLED, enabled)
                    .put(GroupMessage.FIELD_ALARM_RINGING, ringing);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONArray alarms(JSONObject... rows) {
        JSONArray array = new JSONArray();
        for (JSONObject row : rows) {
            array.put(row);
        }
        return array;
    }

    /** Builds a payload object from key/value pairs without checked-exception noise. */
    private static JSONObject json(Object... keyValues) {
        try {
            JSONObject object = new JSONObject();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                object.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            return object;
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    /** Records what the alarm layer would do, and what it was told to do. */
    private static final class FakeGateway implements AlarmGateway {
        final List<String> muted = Collections.synchronizedList(new ArrayList<>());
        final List<PendingRequest> applied = Collections.synchronizedList(new ArrayList<>());
        String muteFailure;
        String applyFailure;
        String ringingUid;

        @Override
        public boolean isRinging(@NonNull String alarmUid) {
            return alarmUid.equals(ringingUid);
        }

        @Nullable
        @Override
        public String mute(@NonNull String alarmUid, @NonNull String fromDeviceId) {
            if (muteFailure != null) {
                return muteFailure;
            }
            muted.add(fromDeviceId + "/" + alarmUid);
            return null;
        }

        @Nullable
        @Override
        public String apply(@NonNull PendingRequest request) {
            if (applyFailure != null) {
                return applyFailure;
            }
            applied.add(request);
            return null;
        }
    }
}
