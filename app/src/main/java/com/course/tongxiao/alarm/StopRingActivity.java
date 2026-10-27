package com.course.tongxiao.alarm;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
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
 * <p>Shown over the keyguard: {@code showWhenLocked} + {@code turnScreenOn} are
 * what make an alarm usable at 6 a.m. with the phone locked.
 */
public class StopRingActivity extends Activity {

    public static final String EXTRA_ALARM_ID = AlarmIntents.EXTRA_ALARM_ID;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private AlarmRepository repository;
    private AlarmScheduler scheduler;
    private RingActions actions;
    private int alarmId;

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

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, -1);

        TongxiaoDb db = TongxiaoDb.get(this);
        repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
        scheduler = new AlarmScheduler(this, repository, resolver);
        actions = new RingActions(db, repository, scheduler);

        timeView = findViewById(R.id.stop_time);
        labelView = findViewById(R.id.stop_label);
        stopButton = findViewById(R.id.btn_stop);
        snoozeButton = findViewById(R.id.btn_snooze);

        stopButton.setOnClickListener(v -> finishWithAction(false));
        snoozeButton.setOnClickListener(v -> finishWithAction(true));

        loadAlarm();
    }

    private void loadAlarm() {
        worker.execute(() -> {
            final Alarm alarm = alarmId > 0 ? TongxiaoDb.get(this).alarmDao().getById(alarmId) : null;
            runOnUiThread(() -> {
                if (alarm == null) {
                    finish();
                    return;
                }
                timeView.setText(String.format(java.util.Locale.CHINA, "%02d:%02d",
                        alarm.hour, alarm.minute));
                labelView.setText(alarm.label == null || alarm.label.isEmpty()
                        ? getString(R.string.alarm_default_label) : alarm.label);
            });
        });
    }

    private void finishWithAction(boolean snooze) {
        stopButton.setEnabled(false);
        snoozeButton.setEnabled(false);
        final long fireAt = System.currentTimeMillis();
        worker.execute(() -> {
            Alarm alarm = TongxiaoDb.get(this).alarmDao().getById(alarmId);
            if (alarm != null) {
                if (snooze) {
                    actions.onSnoozed(alarm, fireAt, alarm.snoozeMin);
                } else {
                    actions.onStopped(alarm, fireAt, "user");
                }
            }
            RingService.stop(this, alarmId);
            runOnUiThread(this::finish);
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
