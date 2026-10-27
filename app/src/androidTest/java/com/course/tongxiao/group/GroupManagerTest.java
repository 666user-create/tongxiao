package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.course.tongxiao.group.net.PeerAddress;
import com.course.tongxiao.group.net.PeerLink;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;

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
 * Step 5.2/5.4: the whole group stack over real 127.0.0.1 sockets — two
 * GroupManagers (each with its own DB, settings and identity) discover, handshake,
 * exchange CMD_MUTE/CMD_ACK and detect the peer going offline.
 *
 * <p>This is the end-to-end proof that the transport + handshake + permission
 * layer work together; NSD is bypassed by injecting the peer address directly.
 */
@RunWith(AndroidJUnit4.class)
public class GroupManagerTest {

    private static final String GROUP_CODE = "888888";

    private TongxiaoDb dbA;
    private TongxiaoDb dbB;
    private GroupIdentity identityA;
    private GroupIdentity identityB;
    private GroupManager managerA;
    private GroupManager managerB;
    private FakeGateway gatewayA;
    private FakeGateway gatewayB;

    /** Messages B received from A (where the ACKs arrive). */
    private final List<GroupMessage> inboundAtB =
            Collections.synchronizedList(new ArrayList<>());
    /** Messages A received from B. */
    private final List<GroupMessage> inboundAtA =
            Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        dbA = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();
        dbB = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();

        identityA = identity(ctx, "test_group_manager_a");
        identityB = identity(ctx, "test_group_manager_b");

        gatewayA = new FakeGateway();
        gatewayB = new FakeGateway();

        managerA = new GroupManager(dbA, settings(ctx, "test_group_manager_settings_a"),
                identityA, gatewayA, fastConfig());
        managerB = new GroupManager(dbB, settings(ctx, "test_group_manager_settings_b"),
                identityB, gatewayB, fastConfig());
        managerA.setObserver((deviceId, message) -> inboundAtA.add(message));
        managerB.setObserver((deviceId, message) -> inboundAtB.add(message));
    }

    @After
    public void tearDown() {
        managerA.stop();
        managerB.stop();
        dbA.close();
        dbB.close();
    }

    @Test
    public void twoManagers_handshakeEnrolsAndMarksBothSidesOnline() {
        int port = managerA.start();
        assertTrue("server bound to an OS-assigned port", port > 0);
        assertEquals(port, managerA.getPort());

        managerB.onPeerDiscovered("peer-a", new PeerAddress("127.0.0.1", port));

        TestAwait.until("A enrolled B as an online member", 8_000L, () -> isOnline(dbA, identityB.deviceId()));
        TestAwait.until("B enrolled A as an online member", 8_000L, () -> isOnline(dbB, identityA.deviceId()));

        GroupMember memberOfB = dbA.groupMemberDao().getByDeviceId(identityB.deviceId());
        assertNotNull(memberOfB);
        assertEquals(GROUP_CODE, memberOfB.groupKey);
        assertEquals(GroupMember.PERM_MUTE | GroupMember.PERM_SEE, memberOfB.grantedMask);
    }

    @Test
    public void cmdMuteOverTheLink_isAckedOkAndExecuted() {
        handshake();

        assertTrue("peer B has a live link to A",
                managerB.send(identityA.deviceId(),
                        GroupMessage.cmdMute(identityB.deviceId(), "uid-live")));

        TestAwait.until("A executed the mute", 5_000L, () -> !gatewayA.muted.isEmpty());
        assertEquals(identityB.deviceId() + "/uid-live", gatewayA.muted.get(0));

        TestAwait.until("B received CMD_ACK ok=true", 5_000L, () -> {
            GroupMessage ack = findAck(inboundAtB);
            return ack != null && ack.bool(GroupMessage.FIELD_OK, false);
        });
        TestAwait.until("B received the op receipt", 5_000L,
                () -> findNotify(inboundAtB) != null);
    }

    @Test
    public void cmdMuteWithoutPermission_overTheLink_isDeniedAndNotExecuted() {
        handshake();
        // A revokes everything B may do; the denial must happen on A.
        dbA.groupMemberDao().setGrantedMask(identityB.deviceId(), 0);

        assertTrue(managerB.send(identityA.deviceId(),
                GroupMessage.cmdMute(identityB.deviceId(), "uid-live")));

        TestAwait.until("B received CMD_ACK ok=false PERMISSION_DENIED", 5_000L, () -> {
            GroupMessage ack = findAck(inboundAtB);
            return ack != null
                    && !ack.bool(GroupMessage.FIELD_OK, true)
                    && GroupProtocol.REASON_PERMISSION_DENIED.equals(
                            ack.string(GroupMessage.FIELD_REASON, ""));
        });
        TestAwait.sleep(200L);
        assertTrue("the alarm must not be muted", gatewayA.muted.isEmpty());
    }

    @Test
    public void setRequestOverTheLink_landsInPendingRequests() {
        handshake();
        dbA.groupMemberDao().setGrantedMask(identityB.deviceId(), GroupMember.PERM_SET);

        assertTrue(managerB.send(identityA.deviceId(), GroupMessage.setRequest(
                "req-live", "REMOVE_ALARM", json("alarmUid", "uid-live"))));

        TestAwait.until("A queued the pending request", 5_000L, () -> {
            PendingRequest request = dbA.pendingRequestDao().getByReqId("req-live");
            return request != null && request.status == PendingRequest.Status.PENDING;
        });
    }

    @Test
    public void stoppingAPeer_marksTheOtherSideOffline() {
        handshake();

        managerB.stop();

        TestAwait.until("A marked B offline after the disconnect", 5_000L,
                () -> !isOnline(dbA, identityB.deviceId()));
    }

    @Test
    public void broadcastSync_pushesTheSharedSchedule() {
        dbA.alarmDao().insert(sharedAlarm());
        handshake();

        TestAwait.until("A sent the schedule push", 5_000L, () -> managerA.broadcastSync() >= 1);
        TestAwait.until("B mirrored the schedule", 5_000L,
                () -> !dbB.remoteAlarmDao().getAll(identityA.deviceId()).isEmpty());
    }

    // ---- helpers ----

    private void handshake() {
        int port = managerA.start();
        assertTrue(port > 0);
        managerB.onPeerDiscovered("peer-a", new PeerAddress("127.0.0.1", port));
        TestAwait.until("both sides know each other", 8_000L,
                () -> isOnline(dbA, identityB.deviceId()) && isOnline(dbB, identityA.deviceId()));
    }

    private static boolean isOnline(TongxiaoDb db, String deviceId) {
        GroupMember member = db.groupMemberDao().getByDeviceId(deviceId);
        return member != null && member.online;
    }

    private static PeerLink.Config fastConfig() {
        return new PeerLink.Config(100L, 800L, 50, 100, 200);
    }

    private static GroupIdentity identity(Context ctx, String prefsName) {
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        GroupIdentity identity = new GroupIdentity(prefs);
        assertTrue(identity.setGroupCode(GROUP_CODE));
        return identity;
    }

    private static SettingsStore settings(Context ctx, String prefsName) {
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return new SettingsStore(prefs);
    }

    @Nullable
    private static GroupMessage findAck(List<GroupMessage> inbox) {
        synchronized (inbox) {
            for (GroupMessage message : inbox) {
                if (message.type() == GroupMessage.Type.CMD_ACK) {
                    return message;
                }
            }
        }
        return null;
    }

    @Nullable
    private static GroupMessage findNotify(List<GroupMessage> inbox) {
        synchronized (inbox) {
            for (GroupMessage message : inbox) {
                if (message.type() == GroupMessage.Type.NOTIFY_OP) {
                    return message;
                }
            }
        }
        return null;
    }

    private static Alarm sharedAlarm() {
        Alarm alarm = new Alarm();
        alarm.label = "早八";
        alarm.hour = 6;
        alarm.minute = 40;
        alarm.enabled = true;
        alarm.sharedToGroup = true;
        alarm.repeatMode = Alarm.RepeatMode.WORKDAY_SMART;
        return alarm;
    }

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

    private static final class FakeGateway implements AlarmGateway {
        final List<String> muted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean isRinging(@NonNull String alarmUid) {
            return false;
        }

        @Nullable
        @Override
        public String mute(@NonNull String alarmUid, @NonNull String fromDeviceId) {
            muted.add(fromDeviceId + "/" + alarmUid);
            return null;
        }

        @Nullable
        @Override
        public String apply(@NonNull PendingRequest request) {
            return null;
        }
    }
}
