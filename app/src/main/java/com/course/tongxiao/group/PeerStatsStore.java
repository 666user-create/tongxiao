package com.course.tongxiao.group;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Last-known leaderboard snapshot of every peer (offline-first, design §9 note).
 *
 * <p>Why SharedPreferences and not Room: these rows are throw-away cache, there is
 * one per member (≤ a few), and nothing joins them against alarm data. A new table
 * would mean a schema migration for data a group mate can re-send in one packet.
 *
 * <p><b>Trust model:</b> everything arriving here is re-validated — the sender is
 * never trusted (design.md §8.2). Invalid snapshots are dropped as a whole
 * ({@link #normalizeAndStore} returns null), out-of-order snapshots never overwrite
 * newer data, and a snapshot older than 24h is kept but flagged {@code stale}
 * instead of being hidden.
 */
public final class PeerStatsStore {

    private static final String TAG = "PeerStatsStore";
    private static final String FILE = "tongxiao_peer_stats";

    /** Snapshots older than this are shown greyed out, never deleted. */
    static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;

    static final int MAX_NICK_LENGTH = 20;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 30;
    static final int MIN_COUNT = 0;
    static final int MAX_COUNT = 100_000;
    /** Last minute of a day: anything above this is not a clock reading at all. */
    static final int MAX_MEDIAN_MINUTE = 24 * 60 - 1;

    private static final String KEY_RECEIVED_AT = "receivedAt";

    private final SharedPreferences prefs;

    public PeerStatsStore(@NonNull Context appContext) {
        this(appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    /** Test seam: isolated prefs per test. */
    @VisibleForTesting
    public PeerStatsStore(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Validates, clamps and stores one incoming snapshot.
     *
     * <p>Entry point for {@code STATS_PUSH}: called on the peer link's reader thread.
     *
     * @return the normalized JSON that was stored, or null when the snapshot must be
     *         dropped (invalid, or superseded by a newer one — a null return always
     *         means "nothing was written")
     */
    @Nullable
    public synchronized String normalizeAndStore(@NonNull JSONObject payload) {
        String deviceId = payload.optString(GroupMessage.FIELD_STATS_DEVICE_ID, "").trim();
        if (!GroupProtocol.isValidDeviceId(deviceId)) {
            return null;
        }
        int median = normalizeMedian(payload.optInt(
                GroupMessage.FIELD_STATS_MEDIAN_STOP, PeerSnapshot.NO_DATA));
        if (median == Integer.MIN_VALUE) {
            return null; // e.g. 1500: not a minute-of-day, so the whole row is junk
        }
        long sentAt = payload.optLong(GroupMessage.FIELD_STATS_SENT_AT, 0L);
        if (sentAt <= 0L) {
            return null; // without an order stamp the row could shuffle the ranking
        }
        PeerSnapshot existing = parse(prefs.getString(deviceId, null));
        if (existing != null && existing.sentAt >= sentAt) {
            return null; // out-of-order / duplicate delivery: keep the newer row
        }
        try {
            JSONObject normalized = new JSONObject();
            normalized.put(GroupMessage.FIELD_STATS_DEVICE_ID, deviceId);
            normalized.put(GroupMessage.FIELD_NICK, RequestPayloads.trim(
                    payload.optString(GroupMessage.FIELD_NICK, ""), MAX_NICK_LENGTH));
            normalized.put(GroupMessage.FIELD_STATS_DAYS, clamp(payload.optInt(
                    GroupMessage.FIELD_STATS_DAYS, MIN_DAYS), MIN_DAYS, MAX_DAYS));
            normalized.put(GroupMessage.FIELD_STATS_MEDIAN_STOP, median);
            normalized.put(GroupMessage.FIELD_STATS_SNOOZE, clamp(payload.optInt(
                    GroupMessage.FIELD_STATS_SNOOZE, 0), MIN_COUNT, MAX_COUNT));
            normalized.put(GroupMessage.FIELD_STATS_MUTED, clamp(payload.optInt(
                    GroupMessage.FIELD_STATS_MUTED, 0), MIN_COUNT, MAX_COUNT));
            normalized.put(GroupMessage.FIELD_STATS_SENT_AT, sentAt);
            normalized.put(KEY_RECEIVED_AT, System.currentTimeMillis());
            String json = normalized.toString();
            prefs.edit().putString(deviceId, json).apply();
            return json;
        } catch (JSONException impossible) {
            // Only non-finite numbers can throw here, and every value is an int/long.
            Log.w(TAG, "could not encode snapshot for " + deviceId);
            return null;
        }
    }

    /**
     * Every stored snapshot, earliest wake-up first; peers without data sort last.
     * Corrupted rows are skipped rather than crashing the ranking.
     */
    @NonNull
    public List<PeerSnapshot> all() {
        Map<String, ?> entries = prefs.getAll();
        List<PeerSnapshot> snapshots = new ArrayList<>(entries.size());
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            PeerSnapshot snapshot = parse((String) entry.getValue());
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        Collections.sort(snapshots, BY_WAKE_UP_TIME);
        return snapshots;
    }

    /** Leaving-group cleanup (§3.3): drop one peer's cached snapshot. */
    public void remove(@NonNull String deviceId) {
        prefs.edit().remove(deviceId).apply();
    }

    /** Leaving-group cleanup: drop every cached snapshot. */
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    // ---- internals ----

    /**
     * @return the stored minute, {@link PeerSnapshot#NO_DATA} for a cold-start row,
     *         or {@link Integer#MIN_VALUE} when the value is not a valid clock time
     */
    private static int normalizeMedian(int median) {
        if (median > MAX_MEDIAN_MINUTE) {
            return Integer.MIN_VALUE;
        }
        return median < 0 ? PeerSnapshot.NO_DATA : median;
    }

    @Nullable
    private static PeerSnapshot parse(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(json);
            String deviceId = object.optString(GroupMessage.FIELD_STATS_DEVICE_ID, "").trim();
            if (!GroupProtocol.isValidDeviceId(deviceId)) {
                return null;
            }
            int median = normalizeMedian(object.optInt(
                    GroupMessage.FIELD_STATS_MEDIAN_STOP, PeerSnapshot.NO_DATA));
            return new PeerSnapshot(
                    deviceId,
                    RequestPayloads.trim(object.optString(GroupMessage.FIELD_NICK, ""),
                            MAX_NICK_LENGTH),
                    object.optInt(GroupMessage.FIELD_STATS_DAYS, MIN_DAYS),
                    median == Integer.MIN_VALUE ? PeerSnapshot.NO_DATA : median,
                    object.optInt(GroupMessage.FIELD_STATS_SNOOZE, 0),
                    object.optInt(GroupMessage.FIELD_STATS_MUTED, 0),
                    object.optLong(GroupMessage.FIELD_STATS_SENT_AT, 0L),
                    object.optLong(KEY_RECEIVED_AT, 0L));
        } catch (JSONException corrupted) {
            Log.w(TAG, "skipping corrupted snapshot row");
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Earliest wake-up first; peers without data last; stable tie-break by device. */
    private static final Comparator<PeerSnapshot> BY_WAKE_UP_TIME = (a, b) -> {
        if (a.hasData() != b.hasData()) {
            return a.hasData() ? -1 : 1;
        }
        if (a.hasData() && a.medianStopMinute != b.medianStopMinute) {
            return Integer.compare(a.medianStopMinute, b.medianStopMinute);
        }
        return a.deviceId.compareTo(b.deviceId);
    };
}
