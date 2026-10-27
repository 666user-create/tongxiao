package com.course.tongxiao.group;

import androidx.annotation.NonNull;

/**
 * One peer's leaderboard snapshot, as last received over the group link.
 *
 * <p>Offline-first by design: a snapshot is kept and shown even when its sender is
 * gone; only its {@link #stale} flag changes, so a stale row is greyed out by the
 * UI instead of disappearing (a ranking that empties itself whenever the Wi-Fi
 * hiccups would be worse than useless).
 */
public final class PeerSnapshot {

    /**
     * {@link #medianStopMinute} value for "too few samples to judge" (cold start).
     * Such a row is still listed, but sorted after everyone with real data.
     */
    public static final int NO_DATA = -1;

    @NonNull
    public final String deviceId;
    @NonNull
    public final String nick;

    /** Statistic window in days, already clamped to 1..30. */
    public final int days;

    /** Median stop time as minute-of-day (0..1439), or {@link #NO_DATA}. */
    public final int medianStopMinute;

    public final int snoozeCount;
    public final int mutedCount;

    /** When the sender produced the snapshot (epoch millis). */
    public final long sentAt;
    /** When this device received it (epoch millis). */
    public final long receivedAt;

    /** True when the snapshot is older than 24h: still listed, just greyed out. */
    public final boolean stale;

    PeerSnapshot(@NonNull String deviceId, @NonNull String nick, int days,
                 int medianStopMinute, int snoozeCount, int mutedCount,
                 long sentAt, long receivedAt) {
        this.deviceId = deviceId;
        this.nick = nick;
        this.days = days;
        this.medianStopMinute = medianStopMinute;
        this.snoozeCount = snoozeCount;
        this.mutedCount = mutedCount;
        this.sentAt = sentAt;
        this.receivedAt = receivedAt;
        this.stale = receivedAt > 0 && System.currentTimeMillis() - receivedAt > PeerStatsStore.STALE_AFTER_MS;
    }

    /** @return true when this peer has enough samples for a ranking position. */
    public boolean hasData() {
        return medianStopMinute >= 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "PeerSnapshot{" + nick + ", median=" + medianStopMinute + ", stale=" + stale + "}";
    }
}
