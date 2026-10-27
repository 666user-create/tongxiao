package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.group.protocol.GroupMessage;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Offline-first leaderboard cache: everything arriving from a peer is re-validated
 * here, and a stale snapshot must still be listed (greyed out) instead of vanishing.
 *
 * <p>Each test asserts both the return value of {@code normalizeAndStore} (null =
 * nothing written) and the stored content, so "silently accepted garbage" and
 * "silently dropped good data" are both caught.
 */
@RunWith(AndroidJUnit4.class)
public class PeerStatsStoreTest {

    private SharedPreferences prefs;
    private PeerStatsStore store;

    @Before
    public void setUp() {
        prefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("test_peer_stats", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new PeerStatsStore(prefs);
    }

    private static JSONObject snapshot(Object... overrides) {
        JSONObject payload = new JSONObject();
        try {
            payload.put(GroupMessage.FIELD_STATS_DEVICE_ID, "peer-a");
            payload.put(GroupMessage.FIELD_NICK, "小明");
            payload.put(GroupMessage.FIELD_STATS_DAYS, 7);
            payload.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, 445);
            payload.put(GroupMessage.FIELD_STATS_SNOOZE, 12);
            payload.put(GroupMessage.FIELD_STATS_MUTED, 3);
            payload.put(GroupMessage.FIELD_STATS_SENT_AT, 1_000L);
            for (int i = 0; i + 1 < overrides.length; i += 2) {
                String key = String.valueOf(overrides[i]);
                Object value = overrides[i + 1];
                if (value == null) {
                    payload.remove(key);
                } else {
                    payload.put(key, value);
                }
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        return payload;
    }

    @Test
    public void validSnapshot_isStoredWithReceiveTime() {
        long before = System.currentTimeMillis();

        String stored = store.normalizeAndStore(snapshot());

        assertNotNull(stored);
        List<PeerSnapshot> all = store.all();
        assertEquals(1, all.size());
        PeerSnapshot row = all.get(0);
        assertEquals("peer-a", row.deviceId);
        assertEquals("小明", row.nick);
        assertEquals(7, row.days);
        assertEquals(445, row.medianStopMinute);
        assertEquals(12, row.snoozeCount);
        assertEquals(3, row.mutedCount);
        assertEquals(1_000L, row.sentAt);
        assertTrue("receivedAt is stamped by us", row.receivedAt >= before);
        assertFalse("fresh data is not stale", row.stale);
    }

    @Test
    public void impossibleWakeUpMinute_discardsTheWholeSnapshot() {
        assertNull("1500 is not a minute of the day", store.normalizeAndStore(
                snapshot(GroupMessage.FIELD_STATS_MEDIAN_STOP, 1500)));
        assertTrue("nothing may be written for a rejected snapshot", store.all().isEmpty());
    }

    @Test
    public void negativeWakeUpMinute_isTreatedAsNoDataAndStillStored() {
        // Cold start (too few samples) is a legitimate state, not corruption.
        assertNotNull(store.normalizeAndStore(
                snapshot(GroupMessage.FIELD_STATS_MEDIAN_STOP, -1)));

        List<PeerSnapshot> all = store.all();
        assertEquals(1, all.size());
        assertFalse(all.get(0).hasData());
        assertEquals(PeerSnapshot.NO_DATA, all.get(0).medianStopMinute);
    }

    @Test
    public void longNick_isTruncatedTo20Chars() {
        StringBuilder nick = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            nick.append('小');
        }
        assertNotNull(store.normalizeAndStore(snapshot(GroupMessage.FIELD_NICK, nick.toString())));

        assertEquals(PeerStatsStore.MAX_NICK_LENGTH, store.all().get(0).nick.length());
    }

    @Test
    public void negativeCounts_areClampedToZeroAndHugeOnesToTheCap() {
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_SNOOZE, -5,
                GroupMessage.FIELD_STATS_MUTED, 999_999)));

        PeerSnapshot row = store.all().get(0);
        assertEquals(0, row.snoozeCount);
        assertEquals(PeerStatsStore.MAX_COUNT, row.mutedCount);
    }

    @Test
    public void days_areClampedTo1Through30() {
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DAYS, 0, GroupMessage.FIELD_STATS_DEVICE_ID, "a")));
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DAYS, 99, GroupMessage.FIELD_STATS_DEVICE_ID, "b")));

        List<PeerSnapshot> all = store.all();
        assertEquals(1, all.get(0).days);
        assertEquals(30, all.get(1).days);
    }

    @Test
    public void invalidDeviceId_orMissingSentAt_isRejected() {
        assertNull("blank deviceId", store.normalizeAndStore(
                snapshot(GroupMessage.FIELD_STATS_DEVICE_ID, "   ")));
        assertNull("no sentAt", store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "peer-b", GroupMessage.FIELD_STATS_SENT_AT, null)));
        assertNull("sentAt 0", store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "peer-c", GroupMessage.FIELD_STATS_SENT_AT, 0L)));

        assertTrue(store.all().isEmpty());
    }

    @Test
    public void outOfOrderSnapshot_neverOverwritesNewerData() {
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_SENT_AT, 200L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 400)));
        assertNull("an older packet is ignored", store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_SENT_AT, 100L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 500)));

        List<PeerSnapshot> all = store.all();
        assertEquals(1, all.size());
        assertEquals("the newer snapshot wins", 400, all.get(0).medianStopMinute);
        assertEquals(200L, all.get(0).sentAt);

        // An equal stamp is not newer either: keep what we have.
        assertNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_SENT_AT, 200L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 500)));
        assertEquals(400, store.all().get(0).medianStopMinute);
    }

    @Test
    public void snapshotOlderThan24h_isStillListedButFlaggedStale() {
        assertNotNull(store.normalizeAndStore(snapshot()));
        // Rewrite the receive stamp 25h into the past: a stale row must be shown.
        String json = prefs.getString("peer-a", null);
        assertNotNull(json);
        try {
            JSONObject row = new JSONObject(json);
            row.put("receivedAt", System.currentTimeMillis() - 25L * 60 * 60 * 1000);
            prefs.edit().putString("peer-a", row.toString()).commit();
        } catch (JSONException e) {
            throw new AssertionError(e);
        }

        List<PeerSnapshot> all = store.all();
        assertEquals("a stale snapshot is not hidden", 1, all.size());
        assertTrue(all.get(0).stale);
    }

    @Test
    public void all_sortsByWakeUpTimeAndPutsNoDataLast() {
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "wakes-at-8", GroupMessage.FIELD_STATS_SENT_AT, 1L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 480)));
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "wakes-at-630", GroupMessage.FIELD_STATS_SENT_AT, 1L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 390)));
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "no-data", GroupMessage.FIELD_STATS_SENT_AT, 1L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, -1)));
        assertNotNull(store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_DEVICE_ID, "wakes-at-710", GroupMessage.FIELD_STATS_SENT_AT, 1L,
                GroupMessage.FIELD_STATS_MEDIAN_STOP, 430)));

        List<PeerSnapshot> all = store.all();
        assertEquals(4, all.size());
        assertEquals("wakes-at-630", all.get(0).deviceId);
        assertEquals("wakes-at-710", all.get(1).deviceId);
        assertEquals("wakes-at-8", all.get(2).deviceId);
        assertEquals("peers without data sort last", "no-data", all.get(3).deviceId);
    }

    @Test
    public void all_onAnEmptyStore_returnsAnEmptyList() {
        assertTrue(store.all().isEmpty());
    }

    @Test
    public void all_skipsCorruptedRowsInsteadOfCrashing() {
        assertNotNull(store.normalizeAndStore(snapshot()));
        prefs.edit()
                .putString("broken-json", "{not json")
                .putString("no-device-id", "{\"sentAt\":5}")
                .putString("wrong-type", "[]")
                .commit();

        List<PeerSnapshot> all = store.all();

        assertEquals("only the valid row survives", 1, all.size());
        assertEquals("peer-a", all.get(0).deviceId);
    }

    @Test
    public void remove_clearsOnlyThatMember() {
        assertNotNull(store.normalizeAndStore(snapshot(GroupMessage.FIELD_STATS_DEVICE_ID, "a")));
        assertNotNull(store.normalizeAndStore(snapshot(GroupMessage.FIELD_STATS_DEVICE_ID, "b")));

        store.remove("a");

        List<PeerSnapshot> all = store.all();
        assertEquals(1, all.size());
        assertEquals("b", all.get(0).deviceId);
    }

    @Test
    public void clearAll_emptiesTheCache() {
        assertNotNull(store.normalizeAndStore(snapshot(GroupMessage.FIELD_STATS_DEVICE_ID, "a")));
        assertNotNull(store.normalizeAndStore(snapshot(GroupMessage.FIELD_STATS_DEVICE_ID, "b")));

        store.clearAll();

        assertTrue(store.all().isEmpty());
    }

    @Test
    public void normalizeAndStore_returnsTheNormalizedJsonThatWasStored() {
        String stored = store.normalizeAndStore(snapshot(
                GroupMessage.FIELD_STATS_SNOOZE, -1));

        assertNotNull(stored);
        try {
            JSONObject parsed = new JSONObject(stored);
            assertEquals(0, parsed.getInt(GroupMessage.FIELD_STATS_SNOOZE));
            assertEquals("peer-a", parsed.getString(GroupMessage.FIELD_STATS_DEVICE_ID));
            assertTrue(parsed.has("receivedAt"));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
