package com.course.tongxiao.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.course.tongxiao.R;
import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.challenge.ChallengeConfig;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 3.2: create / edit one alarm (design.md §7 ②).
 *
 * <p>Scope note: the challenge and weather sections arrive with their engines
 * (Step 4.2 / 6.2) instead of being stubbed here.
 *
 * <p>Saving always re-registers with AlarmManager (or cancels when disabled) so
 * the schedule can never drift from the database.
 */
public class EditAlarmActivity extends AppCompatActivity {

    public static final String EXTRA_ALARM_ID = "alarm_id";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TongxiaoDb db;
    private AlarmRepository repository;
    private AlarmScheduler scheduler;

    private int alarmId;
    private Alarm existing;

    private int hour = 7;
    private int minute = 30;

    private MaterialButton timeButton;
    private EditText labelInput;
    private RadioGroup repeatGroup;
    private MaterialButtonToggleGroup dayGroup;
    private MaterialSwitch skipHolidaysSwitch;
    private Slider snoozeSlider;
    private MaterialSwitch vibrateSwitch;
    private MaterialSwitch challengeSwitch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_alarm);

        db = TongxiaoDb.get(this);
        repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
        scheduler = new AlarmScheduler(this, repository, resolver);

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, 0);

        MaterialToolbar toolbar = findViewById(R.id.edit_toolbar);
        toolbar.setTitle(alarmId > 0 ? R.string.edit_title_edit : R.string.edit_title_new);
        toolbar.inflateMenu(R.menu.menu_edit_alarm);
        toolbar.setOnMenuItemClickListener(this::onMenu);
        toolbar.setNavigationOnClickListener(v -> finish());

        timeButton = findViewById(R.id.edit_time);
        labelInput = findViewById(R.id.edit_label);
        repeatGroup = findViewById(R.id.edit_repeat_group);
        dayGroup = findViewById(R.id.edit_day_group);
        skipHolidaysSwitch = findViewById(R.id.edit_skip_holidays);
        snoozeSlider = findViewById(R.id.edit_snooze);
        vibrateSwitch = findViewById(R.id.edit_vibrate);
        challengeSwitch = findViewById(R.id.edit_challenge);

        timeButton.setOnClickListener(v -> openTimePicker());
        repeatGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());

        if (alarmId > 0) {
            loadExisting();
        } else {
            repeatGroup.check(R.id.repeat_workday_smart);
        }
        updateTimeLabel();
        updateVisibility();
    }

    private boolean onMenu(MenuItem item) {
        if (item.getItemId() == R.id.action_save) {
            save();
            return true;
        }
        if (item.getItemId() == R.id.action_delete) {
            delete();
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_edit_alarm, menu);
        // Only an existing alarm can be deleted.
        menu.findItem(R.id.action_delete).setVisible(alarmId > 0);
        return true;
    }

    private void openTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(R.string.edit_time_title)
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            hour = picker.getHour();
            minute = picker.getMinute();
            updateTimeLabel();
        });
        picker.show(getSupportFragmentManager(), "time_picker");
    }

    private void updateTimeLabel() {
        timeButton.setText(String.format(Locale.CHINA, "%02d:%02d", hour, minute));
    }

    private void updateVisibility() {
        boolean custom = repeatGroup.getCheckedRadioButtonId() == R.id.repeat_custom;
        dayGroup.setVisibility(custom ? android.view.View.VISIBLE : android.view.View.GONE);
        // skipHolidays only affects CUSTOM (design.md §3.1 single source of truth).
        skipHolidaysSwitch.setVisibility(custom ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void loadExisting() {
        worker.execute(() -> {
            final Alarm loaded = db.alarmDao().getById(alarmId);
            runOnUiThread(() -> {
                if (loaded == null) {
                    finish();
                    return;
                }
                existing = loaded;
                hour = loaded.hour;
                minute = loaded.minute;
                updateTimeLabel();
                labelInput.setText(loaded.label);
                snoozeSlider.setValue(Math.max(5, Math.min(30, loaded.snoozeMin)));
                vibrateSwitch.setChecked(loaded.vibrate);
                skipHolidaysSwitch.setChecked(loaded.skipHolidays);
                challengeSwitch.setChecked(!TextUtils.isEmpty(loaded.challengeOverrideJson)
                        && ChallengeConfig.fromJson(loaded.challengeOverrideJson).mode
                        != ChallengeConfig.Mode.DIRECT);
                applyRepeatToUi(loaded);
            });
        });
    }

    private void applyRepeatToUi(Alarm alarm) {
        switch (alarm.repeatMode) {
            case DAILY:
                repeatGroup.check(R.id.repeat_daily);
                break;
            case CUSTOM:
                repeatGroup.check(R.id.repeat_custom);
                checkDays(alarm.dayMask);
                break;
            case ONCE:
                repeatGroup.check(R.id.repeat_once);
                break;
            case WORKDAY_SMART:
            default:
                repeatGroup.check(R.id.repeat_workday_smart);
                break;
        }
        updateVisibility();
    }

    private static final int[] DAY_IDS = {
            R.id.day_mon, R.id.day_tue, R.id.day_wed, R.id.day_thu,
            R.id.day_fri, R.id.day_sat, R.id.day_sun,
    };

    private void checkDays(int dayMask) {
        dayGroup.clearChecked();
        for (int i = 0; i < DAY_IDS.length; i++) {
            if ((dayMask & (1 << i)) != 0) {
                dayGroup.check(DAY_IDS[i]);
            }
        }
    }

    private int readDayMask() {
        int mask = 0;
        java.util.List<Integer> checked = dayGroup.getCheckedButtonIds();
        for (int i = 0; i < DAY_IDS.length; i++) {
            if (checked.contains(DAY_IDS[i])) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private Alarm.RepeatMode readRepeatMode() {
        int checked = repeatGroup.getCheckedRadioButtonId();
        if (checked == R.id.repeat_daily) {
            return Alarm.RepeatMode.DAILY;
        }
        if (checked == R.id.repeat_custom) {
            return Alarm.RepeatMode.CUSTOM;
        }
        if (checked == R.id.repeat_once) {
            return Alarm.RepeatMode.ONCE;
        }
        return Alarm.RepeatMode.WORKDAY_SMART;
    }

    private void save() {
        Alarm.RepeatMode mode = readRepeatMode();
        int dayMask = readDayMask();
        if (mode == Alarm.RepeatMode.CUSTOM && dayMask == 0) {
            Toast.makeText(this, R.string.edit_need_day, Toast.LENGTH_SHORT).show();
            return;
        }

        final Alarm alarm = existing != null ? existing : new Alarm();
        alarm.hour = hour;
        alarm.minute = minute;
        alarm.label = labelInput.getText() == null ? "" : labelInput.getText().toString().trim();
        alarm.repeatMode = mode;
        alarm.dayMask = dayMask;
        alarm.skipHolidays = skipHolidaysSwitch.isChecked();
        alarm.snoozeMin = (int) snoozeSlider.getValue();
        alarm.vibrate = vibrateSwitch.isChecked();
        // Challenge is opt-in (design decision): null = inherit the app default.
        alarm.challengeOverrideJson = challengeSwitch.isChecked()
                ? ChallengeConfig.quizDefault().toJson() : null;
        alarm.enabled = true; // saving an alarm implies enabling it

        worker.execute(() -> {
            try {
                if (alarm.id > 0) {
                    repository.update(alarm);
                } else {
                    alarm.id = (int) repository.insert(alarm);
                }
                scheduler.schedule(alarm);
            } catch (RuntimeException e) {
                // Persisted anyway: the boot/time-change sweep will re-arm it.
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    private void delete() {
        final Alarm alarm = existing;
        if (alarm == null) {
            finish();
            return;
        }
        worker.execute(() -> {
            try {
                scheduler.cancel(alarm);
                repository.deleteWithEvents(alarm);
            } catch (RuntimeException ignored) {
                // best effort; list will refresh from Room
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
