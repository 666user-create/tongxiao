package com.course.tongxiao.ui.stats;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private final TextView[] snoozeRows = new TextView[StatsAggregator.SNOOZE_TOP_N];

    private ExecutorService worker;
    private SleepTrendView trendView;
    private TextView trendCaption;
    private TextView snoozeEmpty;
    private TextView remoteCount;

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
                long since = today.minusDays(StatsAggregator.TREND_DAYS - 1L)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                List<AlarmEvent> events = db.alarmEventDao().getSince(since);
                StatsAggregator.Stats stats = StatsAggregator.aggregate(events, today);

                Map<Integer, String> labels = new HashMap<>();
                for (Alarm alarm : db.alarmDao().getAll()) {
                    labels.put(alarm.id, alarm.label);
                }

                activity.runOnUiThread(() -> {
                    if (isAdded() && getView() != null) {
                        render(stats, labels, today);
                    }
                });
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

    @Override
    public void onDestroyView() {
        if (worker != null) {
            worker.shutdown();
            worker = null;
        }
        super.onDestroyView();
    }
}
