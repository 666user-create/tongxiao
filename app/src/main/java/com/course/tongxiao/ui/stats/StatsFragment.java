package com.course.tongxiao.ui.stats;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 6.3 statistics screen (design.md §7 ⑥): 14-day wake-up trend, snooze
 * ranking, "muted by roommates" counter.
 *
 * <p>Skeleton by design: this fragment is self-contained and the Lead wires the
 * tab entry point (the repository/ViewModel plumbing is unnecessary here — the
 * screen is one read of {@code alarm_events} plus the alarm labels).
 *
 * <p>Threading: the read runs on this fragment's executor and the result is
 * rendered on the UI thread; {@link StatsAggregator} stays pure so the numbers
 * are covered by unit tests instead of by this view.
 */
public class StatsFragment extends Fragment {

    private static final String TAG = "StatsFragment";

    /** Leaderboard window: one week of wake-ups is enough to rank people. */
    private static final int LEADERBOARD_WINDOW_DAYS = 7;
    /** Best-effort broadcast cadence while this screen is visible. */
    private static final long LEADERBOARD_BROADCAST_INTERVAL_MS = 5L * 60 * 1000;

    private final TextView[] snoozeRows = new TextView[StatsAggregator.SNOOZE_TOP_N];

    private ExecutorService worker;
    private SleepTrendView trendView;
    private TextView trendCaption;
    private TextView snoozeEmpty;
    private TextView remoteCount;
    private TextView leaderboardStatus;
    private LinearLayout leaderboardRows;
    private TextView leaderboardEmpty;

    /** Broadcasts at most every 5 minutes: snapshots are best-effort, not chatty. */
    private long lastBroadcastAt;

    private final android.os.Handler ticker =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            load();
            ticker.postDelayed(this, LEADERBOARD_BROADCAST_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        trendView = view.findViewById(R.id.stats_trend);
        trendCaption = view.findViewById(R.id.stats_trend_caption);
        snoozeEmpty = view.findViewById(R.id.stats_snooze_empty);
        remoteCount = view.findViewById(R.id.stats_remote_count);
        snoozeRows[0] = view.findViewById(R.id.stats_snooze_row_1);
        snoozeRows[1] = view.findViewById(R.id.stats_snooze_row_2);
        snoozeRows[2] = view.findViewById(R.id.stats_snooze_row_3);
        leaderboardStatus = view.findViewById(R.id.stats_leaderboard_status);
        leaderboardRows = view.findViewById(R.id.stats_leaderboard_rows);
        leaderboardEmpty = view.findViewById(R.id.stats_leaderboard_empty);

        // Load as soon as the view exists: a host can add this fragment while the
        // activity is only STARTED (multi-window, or a tab host that caps the
        // lifecycle), in which case onResume never runs and the screen would stay
        // blank. onResume below re-reads when the app comes back to the foreground.
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
        // Keep the leaderboard fresh while this screen is actually visible: without
        // the ticker the snapshot went out once and "synced N minutes ago" only grew.
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, LEADERBOARD_BROADCAST_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    /**
     * The tab host swaps fragments with show/hide (MainActivity pattern), so a
     * hidden→visible transition does NOT trigger {@link #onResume}; reload here
     * or the numbers stay frozen at the first render.
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            load();
        }
    }

    /** Re-reads the log and re-renders; safe to call repeatedly. */
    public void load() {
        if (!isAdded() || getView() == null) {
            return;
        }
        if (worker == null || worker.isShutdown()) {
            // A config change destroys the view but keeps this instance alive.
            worker = Executors.newSingleThreadExecutor();
        }
        final Activity activity = requireActivity();
        final android.content.Context appContext = activity.getApplicationContext();
        final LocalDate today = LocalDate.now();

        worker.execute(() -> {
            try {
                TongxiaoDb db = TongxiaoDb.get(appContext);
                // The leaderboard ranks the last 7 days, so it queries its own window
                // instead of reusing the 14-day trend window (mixing them made the
                // snooze/mute counters silently wider than the median).
                long since = today.minusDays(StatsAggregator.TREND_DAYS - 1L)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long leaderboardSince = today.minusDays(LEADERBOARD_WINDOW_DAYS - 1L)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                List<AlarmEvent> events = db.alarmEventDao().getSince(since);
                List<AlarmEvent> leaderboardEvents = leaderboardSince == since
                        ? events : db.alarmEventDao().getSince(leaderboardSince);
                StatsAggregator.Stats stats = StatsAggregator.aggregate(events, today);
                StatsAggregator.PeerSummary mine =
                        StatsAggregator.summarizeForPeers(leaderboardEvents, today,
                                LEADERBOARD_WINDOW_DAYS);
                // Prefs read stays on the worker: getAll() can hit disk the first
                // time and this is the only place that touches PeerStatsStore.
                List<com.course.tongxiao.group.PeerSnapshot> peers =
                        new com.course.tongxiao.group.PeerStatsStore(appContext).all();

                Map<Integer, String> labels = new HashMap<>();
                for (Alarm alarm : db.alarmDao().getAll()) {
                    labels.put(alarm.id, alarm.label);
                }

                activity.runOnUiThread(() -> {
                    if (isAdded() && getView() != null) {
                        render(stats, labels, today);
                        renderLeaderboard(mine, peers);
                    }
                });

                // Offline-first leaderboard: publish our own summary when a session
                // exists and the last broadcast is old enough. No session (not in a
                // group, or the dorm tab is not open) makes this a silent no-op.
                if (System.currentTimeMillis() - lastBroadcastAt
                        > LEADERBOARD_BROADCAST_INTERVAL_MS) {
                    broadcastSnapshot(mine);
                    lastBroadcastAt = System.currentTimeMillis();
                }
            } catch (RuntimeException e) {
                // A stats screen that fails to load must not take the app down;
                // it keeps the last values and the failure is visible in logcat.
                Log.e(TAG, "failed to load alert statistics", e);
            }
        });
    }

    private void render(StatsAggregator.Stats stats, Map<Integer, String> labels, LocalDate today) {
        trendView.setTrend(stats.trend, today);
        trendCaption.setText(getString(R.string.stats_trend_caption, stats.trend.size()));

        for (int i = 0; i < snoozeRows.length; i++) {
            TextView row = snoozeRows[i];
            if (i < stats.snoozeTop.size()) {
                StatsAggregator.SnoozeRow item = stats.snoozeTop.get(i);
                String label = labels.get(item.alarmId);
                if (label == null || label.isEmpty()) {
                    label = getString(R.string.stats_alarm_deleted);
                }
                row.setText(getString(R.string.stats_snooze_row, label, item.count));
                row.setVisibility(View.VISIBLE);
            } else {
                row.setVisibility(View.GONE);
            }
        }
        snoozeEmpty.setVisibility(stats.snoozeTop.isEmpty() ? View.VISIBLE : View.GONE);

        remoteCount.setText(getString(R.string.stats_remote_count, stats.remoteMutedCount));
    }

    // ---- dorm leaderboard (offline-first snapshots, design.md §7.2) ----

    /**
     * Renders "me + every peer we ever heard from". A peer whose snapshot is older
     * than 24h is greyed out but never hidden: dropping Wi-Fi costs freshness, it
     * must not make the leaderboard disappear.
     */
    private void renderLeaderboard(StatsAggregator.PeerSummary mine,
                                   List<com.course.tongxiao.group.PeerSnapshot> peers) {
        if (leaderboardRows == null || !isAdded()) {
            return;
        }
        leaderboardRows.removeAllViews();

        String mineTitle = mine.days == 0
                ? getString(R.string.stats_leaderboard_no_wake_me)
                : getString(R.string.stats_leaderboard_row,
                getString(R.string.stats_leaderboard_me),
                formatClock(mine.medianStopMinuteOfDay));
        addLeaderboardRow(mineTitle,
                getString(R.string.stats_leaderboard_row_detail,
                        mine.snoozeCount, mine.mutedCount, getString(R.string.stats_time_just_now)),
                false);

        if (peers.isEmpty()) {
            // Exactly one message: either "join a group" or "waiting for the first
            // sync". Showing both read like two different problems.
            leaderboardEmpty.setVisibility(View.GONE);
            leaderboardStatus.setText(hasGroupSession()
                    ? getString(R.string.stats_leaderboard_hint)
                    : getString(R.string.stats_leaderboard_empty_room));
            return;
        }

        leaderboardEmpty.setVisibility(View.GONE);
        int staleCount = 0;
        long newestReceivedAt = 0;
        for (com.course.tongxiao.group.PeerSnapshot peer : peers) {
            if (peer.stale) {
                staleCount++;
            }
            newestReceivedAt = Math.max(newestReceivedAt, peer.receivedAt);
            String wake = peer.hasData()
                    ? getString(R.string.stats_leaderboard_row, peer.nick,
                    formatClock(peer.medianStopMinute))
                    : getString(R.string.stats_leaderboard_no_wake_short, peer.nick);
            addLeaderboardRow(wake,
                    getString(R.string.stats_leaderboard_row_detail,
                            peer.snoozeCount, peer.mutedCount, ageText(peer.receivedAt)),
                    peer.stale);
        }

        String age = ageText(newestReceivedAt);
        leaderboardStatus.setText(staleCount == 0
                ? getString(R.string.stats_leaderboard_status, peers.size(), age)
                : getString(R.string.stats_leaderboard_status_stale,
                peers.size(), age, staleCount));
    }

    private void addLeaderboardRow(String titleText, String detail, boolean stale) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView title = new TextView(requireContext());
        title.setText(titleText);
        title.setTextSize(14f);
        title.setTextColor(requireContext().getColor(stale ? R.color.muted : R.color.ink));

        TextView sub = new TextView(requireContext());
        sub.setText(detail);
        sub.setTextSize(11f);
        sub.setTextColor(requireContext().getColor(R.color.muted));

        row.addView(title);
        row.addView(sub);
        leaderboardRows.addView(row);
    }

    /** Publishes our own summary (deviceId/nick/sentAt are filled in by the manager). */
    private void broadcastSnapshot(StatsAggregator.PeerSummary mine) {
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("days", mine.days);
            payload.put("medianStopMinute",
                    mine.days == 0 ? -1 : mine.medianStopMinuteOfDay);
            payload.put("snoozeCount", mine.snoozeCount);
            payload.put("mutedCount", mine.mutedCount);
            com.course.tongxiao.group.GroupSession.broadcastStats(payload);
        } catch (org.json.JSONException | RuntimeException ignored) {
            // The leaderboard is a nicety: never let it disturb the stats screen.
        }
    }

    /**
     * A session alone is not enough: the dorm tab attaches its manager even before a
     * group exists, so relying on it alone would print "waiting for roommates"
     * instead of "join a group first".
     */
    private boolean hasGroupSession() {
        if (com.course.tongxiao.group.GroupSession.currentOrNull() == null) {
            return false;
        }
        try {
            String code = new com.course.tongxiao.group.GroupIdentity(requireContext()).groupCode();
            return code != null && !code.trim().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String formatClock(int minuteOfDay) {
        int safe = Math.max(0, Math.min(24 * 60 - 1, minuteOfDay));
        return String.format(java.util.Locale.CHINA, "%02d:%02d", safe / 60, safe % 60);
    }

    private String ageText(long timestamp) {
        if (timestamp <= 0) {
            return getString(R.string.stats_time_just_now);
        }
        long minutes = Math.max(0, (System.currentTimeMillis() - timestamp) / 60000L);
        if (minutes < 1) {
            return getString(R.string.stats_time_just_now);
        }
        if (minutes < 60) {
            return getString(R.string.stats_time_minutes, (int) minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return getString(R.string.stats_time_hours, (int) hours);
        }
        return getString(R.string.stats_time_days, (int) (hours / 24));
    }

    @Override
    public void onDestroyView() {
        ticker.removeCallbacks(tick);
        if (worker != null) {
            worker.shutdown();
            worker = null;
        }
        super.onDestroyView();
    }
}
