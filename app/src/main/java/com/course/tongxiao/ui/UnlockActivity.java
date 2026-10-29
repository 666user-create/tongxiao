package com.course.tongxiao.ui;

import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.course.tongxiao.R;
import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.alarm.RingActions;
import com.course.tongxiao.alarm.RingService;
import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.challenge.ChallengeConfig;
import com.course.tongxiao.engine.challenge.ChallengeEngine;
import com.course.tongxiao.engine.challenge.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 4.2/4.3: the challenge between "ringing" and "stopped" (design.md §5.4, §7).
 *
 * <p>Safety rules: the emergency stop is always available (long press 3 s) and is
 * recorded as EMERGENCY_STOP so the adaptive engine can tell it apart from a
 * proper wake-up; any internal failure simply stops the alarm. The challenge must
 * never trap the user or delay silencing the ring.
 */
public class UnlockActivity extends AppCompatActivity implements SensorEventListener {

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_RING_STARTED_AT = "ring_started_at";

    private static final float SHAKE_THRESHOLD = 12f;

    /** ESCALATE adds at most this many harder questions, so the challenge still ends. */
    private static final int MAX_ESCALATIONS = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TongxiaoDb db;
    private RingActions actions;
    private int alarmId;
    private long ringStartedAt;

    private ChallengeConfig config;
    private ChallengeEngine engine;
    private List<Question> session = new ArrayList<>();
    private int index;
    private int wrongAttempts;
    private int escalations;
    private boolean finished;

    private TextView progressView;
    private TextView questionView;
    private LinearLayout optionsContainer;
    private TextView shakeView;
    private TextView feedbackView;
    private final Button[] optionButtons = new Button[4];

    // shake mode state
    private SensorManager sensorManager;
    private int shakesDone;
    private long lastShakeAt;

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
        setContentView(R.layout.activity_unlock);
        com.course.tongxiao.ui.UiInsets.padSystemBars(findViewById(R.id.unlock_root), 8, 8);

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, -1);
        ringStartedAt = getIntent().getLongExtra(EXTRA_RING_STARTED_AT, 0L);
        if (ringStartedAt <= 0) {
            ringStartedAt = System.currentTimeMillis();
        }

        progressView = findViewById(R.id.unlock_progress);
        questionView = findViewById(R.id.unlock_question);
        optionsContainer = findViewById(R.id.unlock_options);
        shakeView = findViewById(R.id.unlock_shake);
        feedbackView = findViewById(R.id.unlock_feedback);
        optionButtons[0] = findViewById(R.id.btn_opt_0);
        optionButtons[1] = findViewById(R.id.btn_opt_1);
        optionButtons[2] = findViewById(R.id.btn_opt_2);
        optionButtons[3] = findViewById(R.id.btn_opt_3);

        registerPredictiveBackBlocker();
        setUpEmergencyStop();

        db = TongxiaoDb.get(this);
        worker.execute(this::loadAndStart);
    }

    // ---- setup ----

    private void loadAndStart() {
        try {
            AlarmRepository repository = new AlarmRepository(db);
            HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
            AlarmScheduler scheduler = new AlarmScheduler(this, repository, resolver);
            actions = new RingActions(db, repository, scheduler);
            // Bundled bank + the questions the user wrote themselves (design.md §5.4).
            engine = ChallengeEngine.fromAssets(getApplicationContext(),
                    new com.course.tongxiao.engine.challenge.CustomQuestionStore(
                            getApplicationContext()).load());

            Alarm alarm = alarmId > 0 ? db.alarmDao().getById(alarmId) : null;
            ChallengeConfig fromAlarm = alarm == null
                    ? null : ChallengeConfig.fromJson(alarm.challengeOverrideJson);
            ChallengeConfig resolved = fromAlarm != null
                    ? fromAlarm
                    : ChallengeConfig.fromJson(new SettingsStore(this).getDefaultChallengeJson());

            main.post(() -> startWith(resolved));
        } catch (RuntimeException e) {
            // Fail open: if the challenge cannot be built, the alarm must still stop.
            main.post(this::passAndStop);
        }
    }

    private void startWith(ChallengeConfig resolved) {
        config = resolved.clamped();
        if (config.mode == ChallengeConfig.Mode.SHAKE) {
            startShakeMode();
            return;
        }
        session = engine.buildSession(config, new Random().nextLong());
        if (session.isEmpty()) {
            passAndStop();
            return;
        }
        showQuestion();
    }

    private void showQuestion() {
        Question question = session.get(index);
        feedbackView.setText("");
        updateProgressLine();
        questionView.setText(question.text);
        setOptionsEnabled(true);
        for (int i = 0; i < optionButtons.length; i++) {
            Button button = optionButtons[i];
            if (i < question.options.size()) {
                button.setVisibility(View.VISIBLE);
                final int chosen = i;
                button.setText(question.options.get(i));
                button.setOnClickListener(v -> onAnswer(question, chosen));
            } else {
                button.setVisibility(View.GONE);
            }
        }
    }

    private void onAnswer(Question question, int chosen) {
        if (finished) {
            return;
        }
        if (question.isCorrect(chosen)) {
            index++;
            if (index >= session.size()) {
                feedbackView.setText(R.string.unlock_correct);
                passAndStop();
            } else {
                showQuestion();
                feedbackView.setText(R.string.unlock_correct);
            }
            return;
        }

        wrongAttempts++;
        updateProgressLine();
        switch (config.wrongPolicy) {
            case ADD_SNOOZE: {
                // Real penalty: the options stay locked for the cooling-off period.
                int penalty = Math.max(5, config.delaySeconds + 10);
                feedbackView.setText(getString(R.string.unlock_wrong_snooze, penalty));
                setOptionsEnabled(false);
                main.postDelayed(() -> {
                    if (finished) {
                        return;
                    }
                    setOptionsEnabled(true);
                    index = Math.min(index + 1, Math.max(0, session.size() - 1));
                    showQuestion();
                }, penalty * 1000L);
                return;
            }
            case ESCALATE: {
                // Real penalty: one extra, harder question is appended (capped so the
                // challenge still ends). Without this the policy was only copy.
                if (escalations < MAX_ESCALATIONS) {
                    escalations++;
                    session.add(ChallengeEngine.generateArithmetic(
                            new java.util.Random(System.currentTimeMillis() + escalations), 3));
                    showQuestion();
                    feedbackView.setText(getString(R.string.unlock_wrong_escalate_hard,
                            MAX_ESCALATIONS - escalations + 1));
                } else {
                    showQuestion();
                    feedbackView.setText(R.string.unlock_wrong_escalate);
                }
                return;
            }
            case RETRY:
            default:
                if (index < session.size()) {
                    showQuestion();
                }
                feedbackView.setText(R.string.unlock_wrong_retry);
                break;
        }
    }

    /** "第 1 / 2 题 · 答错 2 次 · 答对才能停铃" — the penalty count is always visible. */
    private void updateProgressLine() {
        if (progressView == null) {
            return;
        }
        int total = Math.max(1, session.size());
        int shown = Math.min(index + 1, total);
        progressView.setText(wrongAttempts == 0
                ? getString(R.string.unlock_progress, shown, total)
                : getString(R.string.unlock_progress_with_wrong, shown, total, wrongAttempts));
    }

    private void setOptionsEnabled(boolean enabled) {
        for (Button button : optionButtons) {
            button.setEnabled(enabled);
        }
    }

    // ---- shake mode (Step 4.3) ----

    private void startShakeMode() {
        optionsContainer.setVisibility(View.GONE);
        questionView.setVisibility(View.GONE);
        shakeView.setVisibility(View.VISIBLE);
        shakesDone = 0;
        updateShakeText();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accelerometer = sensorManager == null
                ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            passAndStop(); // no sensor: never trap the user
            return;
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    private void updateShakeText() {
        shakeView.setText(getString(R.string.unlock_shake_progress,
                Math.min(shakesDone, config.shakeCount), config.shakeCount));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (finished || config == null || config.mode != ChallengeConfig.Mode.SHAKE) {
            return;
        }
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double magnitude = Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
        long now = System.currentTimeMillis();
        if (magnitude > SHAKE_THRESHOLD && now - lastShakeAt > 250) {
            lastShakeAt = now;
            shakesDone++;
            updateShakeText();
            if (shakesDone >= config.shakeCount) {
                passAndStop();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not used
    }

    // ---- emergency stop ----

    private void setUpEmergencyStop() {
        Button emergency = findViewById(R.id.unlock_emergency);
        emergency.setOnLongClickListener(v -> {
            stopInternal(true);
            return true;
        });
    }

    // ---- outcomes ----

    private void passAndStop() {
        stopInternal(false);
    }

    /**
     * @param emergency true when the user long-pressed the emergency stop; it is
     *                  recorded as EMERGENCY_STOP for the adaptive engine.
     */
    private void stopInternal(boolean emergency) {
        if (finished) {
            return;
        }
        finished = true;
        unregisterSensor();
        // Silence first: the challenge must never delay the sound going away.
        RingService.stop(this, alarmId);
        final long startedAt = ringStartedAt;
        worker.execute(() -> {
            try {
                Alarm alarm = alarmId > 0 ? db.alarmDao().getById(alarmId) : null;
                if (alarm != null && actions != null) {
                    if (emergency) {
                        actions.onEmergencyStop(alarm, startedAt);
                    } else {
                        actions.onStopped(alarm, startedAt, "user");
                    }
                }
            } catch (RuntimeException ignored) {
                // best effort; the ring is already silenced
            } finally {
                main.post(this::finish);
            }
        });
    }

    private void unregisterSensor() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorManager = null;
        }
    }

    @SuppressLint({"MissingSuperCall", "GestureBackNavigation"})
    @Override
    public void onBackPressed() {
        // Deliberately not calling super: finishing here would skip the challenge
        // with one tap. The emergency stop is the documented escape hatch.
        feedbackView.setText(R.string.unlock_back_blocked);
    }

    /** API 33+ predictive back: onBackPressed() is not called for gestures. */
    private void registerPredictiveBackBlocker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> feedbackView.setText(R.string.unlock_back_blocked));
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        // Keep the alarm's heads-up banner off the question text.
        com.course.tongxiao.alarm.RingService.setUiVisible(this, true);
    }


    @Override
    protected void onPause() {
        super.onPause();
        com.course.tongxiao.alarm.RingService.setUiVisible(this, false);
    }

    @Override
    protected void onDestroy() {
        unregisterSensor();
        worker.shutdown();
        super.onDestroy();
    }
}