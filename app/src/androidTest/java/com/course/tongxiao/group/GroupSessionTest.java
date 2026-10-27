package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.group.protocol.GroupMessage;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The facade the stats page uses to reach the live group session: it must publish
 * exactly one manager, withdraw only its own, and be a safe no-op when nobody is
 * reachable (offline-first).
 */
@RunWith(AndroidJUnit4.class)
public class GroupSessionTest {

    private static final String GROUP_CODE = "222333";

    private TongxiaoDb db;
    private GroupManager manager;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();
        SharedPreferences identityPrefs = ctx.getSharedPreferences(
                "test_group_session_identity", Context.MODE_PRIVATE);
        identityPrefs.edit().clear().commit();
        GroupIdentity identity = new GroupIdentity(identityPrefs);
        identity.setGroupCode(GROUP_CODE);
        SharedPreferences settingsPrefs = ctx.getSharedPreferences(
                "test_group_session_settings", Context.MODE_PRIVATE);
        settingsPrefs.edit().clear().commit();
        SharedPreferences statsPrefs = ctx.getSharedPreferences(
                "test_group_session_stats", Context.MODE_PRIVATE);
        statsPrefs.edit().clear().commit();

        manager = new GroupManager(db, new SettingsStore(settingsPrefs), identity,
                new NoopGateway(), new PeerStatsStore(statsPrefs));
        GroupSession.attach(manager);
    }

    @After
    public void tearDown() {
        manager.stop();
        GroupSession.detach(manager);
        db.close();
    }

    @Test
    public void attachThenDetach_publishesAndWithdrawsTheManager() {
        assertSame("the stats page can reach the live session", manager,
                GroupSession.currentOrNull());

        GroupSession.detach(manager);

        assertNull("after the dorm page stops, nothing is published", GroupSession.currentOrNull());
    }

    @Test
    public void detach_ofAnotherManager_doesNotWithdrawTheLiveOne() {
        GroupManager other = new GroupManager(db, new SettingsStore(
                ApplicationProvider.getApplicationContext().getSharedPreferences(
                        "test_group_session_settings_other", Context.MODE_PRIVATE)),
                new GroupIdentity(ApplicationProvider.getApplicationContext()
                        .getSharedPreferences("test_group_session_identity_other",
                                Context.MODE_PRIVATE)),
                new NoopGateway(), new PeerStatsStore(ApplicationProvider.getApplicationContext()
                        .getSharedPreferences("test_group_session_stats_other",
                                Context.MODE_PRIVATE)));

        GroupSession.detach(other);

        assertSame("a stale holder must not clear the current session", manager,
                GroupSession.currentOrNull());
    }

    @Test
    public void broadcastStats_withNoLivePeer_isASafeNoOp() throws JSONException {
        // Attached but not listening (no server started, no links): must not throw.
        JSONObject snapshot = new JSONObject();
        snapshot.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, 420);

        GroupSession.broadcastStats(snapshot);

        // And with no session at all it is still a no-op.
        GroupSession.detach(manager);
        GroupSession.broadcastStats(snapshot);
        assertNull(GroupSession.currentOrNull());
    }

    @Test
    public void broadcastStats_withAForeignDeviceIdInTheSnapshot_isHarmless() throws JSONException {
        // The manager overwrites the identity fields (deviceId/nick/sentAt) and the
        // receiver re-validates them, so a UI-provided deviceId is ignored twice.
        JSONObject snapshot = new JSONObject();
        snapshot.put(GroupMessage.FIELD_STATS_DEVICE_ID, "someone-else");
        snapshot.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, 420);

        assertEquals("no peer links exist: nothing is sent, nothing throws",
                0, manager.broadcastStats(snapshot));
    }

    private static final class NoopGateway implements AlarmGateway {
        @Override
        public boolean isRinging(@NonNull String alarmUid) {
            return false;
        }

        @Nullable
        @Override
        public String mute(@NonNull String alarmUid, @NonNull String fromDeviceId) {
            return null;
        }

        @Nullable
        @Override
        public String apply(@NonNull PendingRequest request) {
            return null;
        }
    }
}
