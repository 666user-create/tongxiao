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
    private PeerStatsStore statsA;
    private PeerStatsStore statsB;
    private SettingsStore settingsA;
    private SettingsStore settingsB;
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

        statsA = statsStore(ctx, "test_group_manager_stats_a");
        statsB = statsStore(ctx, "test_group_manager_stats_b");
        settingsA = settings(ctx, "test_group_manager_settings_a");
        settingsB = settings(ctx, "test_group_manager_settings_b");

        managerA = new GroupManager(dbA, settingsA, identityA, gatewayA, statsA, fastConfig());
        managerB = new GroupManager(dbB, settingsB, identityB, gatewayB, statsB, fastConfig());
        managerA.setObserver((deviceId, message) -> inboundAtA.add(message));
        managerB.setObserver((deviceId, message) -> inboundAtB.add(message));
    }

    @After
    public void tearDown() {
        GroupSession.detach(managerA);
        managerA.stop();
        managerB.stop();
        // Let the reader threads finish in-flight callbacks before the DBs go away:
        // closing a database under a live reader thread would surface as a test-only
        // "connection is closed" crash (the app itself guards against it, but the
        // teardown should not rely on that).
        TestAwait.sleep(300L);
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

        // B mutes A's alarm: ownerDev = A (the alarm's owner = the executing side).
        assertTrue("peer B has a live link to A",
                managerB.send(identityA.deviceId(),
                        GroupMessage.cmdMute(identityA.deviceId(), "uid-live")));

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
    public void ringingAlarm_reachesThePeerMirror_thenTheRemoteMuteStopsIt() {
        // The full demo path: A is ringing → SYNC_PUSH carries ringing → B sees it →
        // B sends CMD_MUTE → A's alarm gateway is invoked and A acknowledges.
        Alarm ringing = sharedAlarm();
        dbA.alarmDao().insert(ringing);
        gatewayA.ringingUid = ringing.uid;
        handshake();

        TestAwait.until("A pushed its schedule", 5_000L, () -> managerA.broadcastSync() >= 1);
        TestAwait.until("B's mirror shows A's alarm ringing", 5_000L,
                () -> !dbB.remoteAlarmDao().getRinging().isEmpty());
        assertEquals(ringing.uid, dbB.remoteAlarmDao().getRinging().get(0).srcAlarmUid);

        TestAwait.until("B can aim the command at A", 5_000L,
                () -> managerB.send(identityA.deviceId(),
                        GroupMessage.cmdMute(identityA.deviceId(), ringing.uid)));

        TestAwait.until("A's gateway was asked to mute", 5_000L,
                () -> !gatewayA.muted.isEmpty());
        assertEquals(identityB.deviceId() + "/" + ringing.uid, gatewayA.muted.get(0));
        TestAwait.until("B got the ok ACK", 5_000L,
                () -> findAckMute(inboundAtB, true) != null);
    }

    @Test
    public void cmdMuteWithoutPermission_overTheLink_isDeniedAndNotExecuted() {
        handshake();
        // A revokes everything B may do; the denial must happen on A.
        dbA.groupMemberDao().setGrantedMask(identityB.deviceId(), 0);

        // The link is a real socket: `handshake()` guarantees the HELLO exchange, but
        // the outgoing link on B can still be registered a beat later. Retry instead of
        // asserting on the first attempt (this flaked only under full-suite load).
        TestAwait.until("B can reach A", 5_000L,
                () -> managerB.send(identityA.deviceId(),
                        GroupMessage.cmdMute(identityA.deviceId(), "uid-live")));

        TestAwait.until("B received a mute ACK denied with PERMISSION_DENIED", 5_000L, () -> {
            GroupMessage ack = findAckMute(inboundAtB, false);
            return ack != null
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

    @Test
    public void broadcastStats_overTheLink_reachesThePeerRankingCache() {
        handshake();

        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, 445);
            snapshot.put(GroupMessage.FIELD_STATS_DAYS, 7);
            snapshot.put(GroupMessage.FIELD_STATS_SNOOZE, 4);
            snapshot.put(GroupMessage.FIELD_STATS_MUTED, 1);
            // deviceId/nick/sentAt are filled in by the manager, not the UI.
            snapshot.put(GroupMessage.FIELD_STATS_DEVICE_ID, "spoofed-identity");
        } catch (JSONException e) {
            throw new AssertionError(e);
        }

        TestAwait.until("A reached its peer", 5_000L, () -> managerA.broadcastStats(snapshot) >= 1);
        TestAwait.until("B stored A's snapshot", 5_000L, () -> !statsB.all().isEmpty());

        PeerSnapshot row = statsB.all().get(0);
        assertEquals("identity is taken from the authenticated link, not the payload",
                identityA.deviceId(), row.deviceId);
        assertEquals(445, row.medianStopMinute);
        assertEquals(0, managerB.coordinator().droppedStats());
    }

    @Test
    public void broadcastStats_whenThePeerHasTheMasterSwitchOff_isDroppedThere() {
        handshake();
        // B refuses all remote traffic; the snapshot must not land in its cache.
        settingsB.setGroupMasterSwitchOn(false);

        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, 480);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        managerA.broadcastStats(snapshot);

        TestAwait.until("B counted the dropped snapshot", 5_000L,
                () -> managerB.coordinator().droppedStats() > 0);
        assertTrue(statsB.all().isEmpty());
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

    private static PeerStatsStore statsStore(Context ctx, String prefsName) {
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return new PeerStatsStore(prefs);
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

    /**
     * Finds the ACK that answers a mute: it carries the {@code MUTE:} tag, so a
     * schedule-push acknowledgement on the same link cannot be mistaken for it.
     */
    @Nullable
    private static GroupMessage findAckMute(List<GroupMessage> inbox, boolean ok) {
        synchronized (inbox) {
            for (GroupMessage message : inbox) {
                if (message.type() == GroupMessage.Type.CMD_ACK
                        && message.bool(GroupMessage.FIELD_OK, false) == ok
                        && message.string(GroupMessage.FIELD_WHAT, "")
                        .startsWith("MUTE:")) {
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
        /** uid that {@link #isRinging(String)} reports as ringing (simulates RingService). */
        volatile String ringingUid;

        @Override
        public boolean isRinging(@NonNull String alarmUid) {
            return alarmUid.equals(ringingUid);
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
    /**
     * Regression: the remote-mute button calls {@code send()} from the UI thread. A
     * direct socket write there throws NetworkOnMainThreadException and takes the app
     * down mid-demo (seen on device before the writer thread existed).
     */
    @Test
    public void send_fromTheMainThread_doesNotThrow() {
        handshake();
        TestAwait.until("link ready", 5_000L, () -> managerA.broadcastSync() >= 0);
        // Give both links a moment to finish the handshake before the main-thread write.
        TestAwait.sleep(300L);

        final boolean[] result = {false};
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result[0] = managerB.send(identityA.deviceId(),
                        GroupMessage.cmdMute(identityA.deviceId(), "uid-main-thread")));
        assertTrue("send() from the UI thread must be accepted (it only enqueues)", result[0]);

        TestAwait.until("the queued frame actually reached A", 5_000L,
                () -> gatewayA.muted.contains(identityB.deviceId() + "/uid-main-thread")
                        || findAckMute(inboundAtB, true) != null);
    }
}
