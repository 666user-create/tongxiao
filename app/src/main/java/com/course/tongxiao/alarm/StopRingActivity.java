package com.course.tongxiao.alarm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen stop screen (design.md §7 ③). Step 2.3: stop / snooze only;
 * Step 4.2 inserts the challenge UI between "ring" and "stop".
 *
 * <p>Safety rules implemented here:
 * <ul>
 *   <li>the ring is silenced FIRST, before any database work, so stopping the
 *       sound never depends on Room succeeding;</li>
 *   <li>back press (gesture or button) stops the alarm instead of leaving it
 *       ringing;</li>
 *   <li>{@code onNewIntent} re-targets this screen when a second alarm fires
 *       while it is still visible ({@code singleInstance} reuses the instance);</li>
 *   <li>a missing alarm row still stops the service, so the ring cannot get stuck.</li>
 * </ul>
 */
public class StopRingActivity extends Activity {

    private static final String TAG = "StopRingActivity";

    public static final String EXTRA_ALARM_ID = AlarmIntents.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = "label";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TongxiaoDb db;
    private RingActions actions;
    private int alarmId;
    private long ringStartedAt;

    private TextView timeView;
    private TextView labelView;
    private Button stopButton;
    private Button snoozeButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_stop_ring);

        db = TongxiaoDb.get(this);
        timeView = findViewById(R.id.stop_time);
        labelView = findViewById(R.id.stop_label);
        stopButton = findViewById(R.id.btn_stop);
        snoozeButton = findViewById(R.id.btn_snooze);

        stopButton.setOnClickListener(v -> stopAndFinish());
        snoozeButton.setOnClickListener(v -> snoozeAndFinish());

        // Heavy wiring (assets parse + scheduler) stays off the first-frame path.
        worker.execute(() -> {
            AlarmRepository repository = new AlarmRepository(db);
            HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
            AlarmScheduler scheduler = new AlarmScheduler(this, repository, resolver);
            actions = new RingActions(db, repository, scheduler);
        });

        registerPredictiveBack();
        applyIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A second alarm fired while this screen was up: re-target the SAME view
        // instance, otherwise the first alarm would be recorded and the second
        // one's one-shot registration would be silently lost.
        resetButtons();
        applyIntent(intent);
    }

    private void applyIntent(@Nullable Intent intent) {
        if (intent == null) {
            stopAndFinish();
            return;
        }
        alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        ringStartedAt = intent.getLongExtra(AlarmIntents.EXTRA_RING_STARTED_AT, 0L);
        if (ringStartedAt <= 0) {
            ringStartedAt = System.currentTimeMillis();
        }
        String labelFromIntent = intent.getStringExtra(EXTRA_LABEL);
        if (!TextUtils.isEmpty(labelFromIntent)) {
            labelView.setText(labelFromIntent);
        }
        loadAlarm();
    }

    private void resetButtons() {
        stopButton.setEnabled(true);
        snoozeButton.setEnabled(true);
    }

    private void loadAlarm() {
        if (alarmId <= 0) {
            stopAndFinish();
            return;
        }
        worker.execute(() -> {
            Alarm alarm = null;
            try {
                alarm = db.alarmDao().getById(alarmId);
            } catch (RuntimeException e) {
                Log.e(TAG, "could not load alarm " + alarmId, e);
            }
            final Alarm loaded = alarm;
            runOnUiThread(() -> {
                if (loaded == null) {
                    // Row gone (deleted concurrently / DB rebuilt): still silence
                    // the ring instead of leaving it screaming.
                    stopAndFinish();
                    return;
                }
                timeView.setText(String.format(java.util.Locale.CHINA, "%02d:%02d",
                        loaded.hour, loaded.minute));
                labelView.setText(TextUtils.isEmpty(loaded.label)
                        ? getString(R.string.alarm_default_label) : loaded.label);
            });
        });
    }

    /** Silences the ring immediately, then records the state change in the background. */
    private void stopAndFinish() {
        disableButtons();
        // 1) Sound first: never let a DB failure keep the alarm ringing.
        RingService.stop(this, alarmId);
        // 2) Then persist + re-arm, isolated from crashes.
        final int id = alarmId;
        final long startedAt = ringStartedAt;
        worker.execute(() -> {
            try {
                Alarm alarm = id > 0 ? db.alarmDao().getById(id) : null;
                if (alarm != null && actions != null) {
                    actions.onStopped(alarm, startedAt, "user");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "stop bookkeeping failed for alarm " + id, e);
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    /** Snooze: silence now, re-arm in N minutes, record it. */
    private void snoozeAndFinish() {
        disableButtons();
        RingService.stop(this, alarmId);
        final int id = alarmId;
        final long startedAt = ringStartedAt;
        worker.execute(() -> {
            try {
                Alarm alarm = id > 0 ? db.alarmDao().getById(id) : null;
                if (alarm != null && actions != null) {
                    actions.onSnoozed(alarm, startedAt, alarm.snoozeMin);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "snooze bookkeeping failed for alarm " + id, e);
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    private void disableButtons() {
        stopButton.setEnabled(false);
        snoozeButton.setEnabled(false);
    }

    @SuppressLint("GestureBackNavigation") // API 33+ path registered via OnBackInvokedCallback
    @Override
    public void onBackPressed() {
        // Leaving the screen must not leave the alarm ringing (BLOCKER fix).
        stopAndFinish();
    }

    /** API 33+ predictive back: onBackPressed() is no longer invoked for gestures. */
    private void registerPredictiveBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::stopAndFinish);
        }
    }

    @Override
    protected void onDestroy() {
        // Graceful shutdown: an interrupt mid-write could lose the STOPPED event
        // and the re-arm, which would silently skip tomorrow's alarm.
        worker.shutdown();
        super.onDestroy();
    }
}
