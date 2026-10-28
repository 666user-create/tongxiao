package com.course.tongxiao.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /** Default cap for the weather advance, inside WeatherClient's 5..20 range. */
    private static final int DEFAULT_ADVANCE_MAX_MIN = 10;

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
    /** Read-out for the slider: Material3 draws no value label of its own. */
    private TextView snoozeValue;
    private MaterialSwitch vibrateSwitch;
    private MaterialButtonToggleGroup challengeModeGroup;
    private TextView questionSourceLabel;
    private MaterialButtonToggleGroup questionSourceGroup;
    private MaterialButton mineButton;
    private TextView questionSourceHint;
    private com.course.tongxiao.engine.challenge.CustomQuestionStore customQuestionStore;
    private MaterialSwitch advanceWeatherSwitch;
    private MaterialSwitch shareToGroupSwitch;

    /** Guards against double-tapping 保存 before the worker finishes. */
    private boolean saving;

    /** True once the row being edited has been read back and the form is populated. */
    private boolean loaded;

    private com.course.tongxiao.engine.NextTriggerCalculator calculator;

    // Step 6.1/6.2 cards
    private View suggestionCard;
    private TextView suggestionTitle;
    private TextView suggestionText;
    private TextView weatherHint;
    private View suggestionActions;
    @Nullable
    private com.course.tongxiao.engine.adaptive.Suggestion pendingSuggestion;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_alarm);

        db = TongxiaoDb.get(this);
        repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
        scheduler = new AlarmScheduler(this, repository, resolver);
        calculator = new com.course.tongxiao.engine.NextTriggerCalculator(resolver);

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, 0);

        MaterialToolbar toolbar = findViewById(R.id.edit_toolbar);
        toolbar.setTitle(alarmId > 0 ? R.string.edit_title_edit : R.string.edit_title_new);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationContentDescription(R.string.dialog_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        // Edge-to-edge: pad the ROOT (not the fixed-height toolbar, which would
        // squeeze its content out). The IME inset must go on the root as well:
        // padding only the ScrollView left the fixed 保存/删除 bar drawn underneath
        // the keyboard, so the primary action was unreachable while typing a name.
        UiInsets.padSystemBarsAndIme(findViewById(R.id.edit_root), 0);

        timeButton = findViewById(R.id.edit_time);
        labelInput = findViewById(R.id.edit_label);
        repeatGroup = findViewById(R.id.edit_repeat_group);
        dayGroup = findViewById(R.id.edit_day_group);
        skipHolidaysSwitch = findViewById(R.id.edit_skip_holidays);
        snoozeSlider = findViewById(R.id.edit_snooze);
        snoozeValue = findViewById(R.id.edit_snooze_value);
        snoozeSlider.addOnChangeListener((slider, value, fromUser) -> renderSnoozeValue());
        renderSnoozeValue();
        vibrateSwitch = findViewById(R.id.edit_vibrate);
        challengeModeGroup = findViewById(R.id.edit_challenge_mode);
        questionSourceLabel = findViewById(R.id.edit_question_source_label);
        questionSourceGroup = findViewById(R.id.edit_question_source);
        mineButton = findViewById(R.id.question_source_mine);
        questionSourceHint = findViewById(R.id.edit_question_source_hint);
        customQuestionStore = new com.course.tongxiao.engine.challenge.CustomQuestionStore(
                getApplicationContext());
        challengeModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateQuestionSourceVisibility();
            }
        });
        questionSourceGroup.check(R.id.question_source_builtin);
        advanceWeatherSwitch = findViewById(R.id.edit_advance_weather);
        shareToGroupSwitch = findViewById(R.id.edit_share_group);

        timeButton.setOnClickListener(v -> openTimePicker());
        repeatGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());

        findViewById(R.id.edit_save).setOnClickListener(v -> save());

        suggestionCard = findViewById(R.id.edit_suggestion_card);
        suggestionTitle = findViewById(R.id.edit_suggestion_title);
        suggestionText = findViewById(R.id.edit_suggestion_text);
        weatherHint = findViewById(R.id.edit_weather_hint);
        suggestionActions = findViewById(R.id.edit_suggestion_actions);
        findViewById(R.id.edit_suggestion_accept).setOnClickListener(v -> {
            if (pendingSuggestion != null) {
                hour = pendingSuggestion.hour;
                minute = pendingSuggestion.minute;
                updateTimeLabel();
            }
            suggestionCard.setVisibility(View.GONE);
            Toast.makeText(this, R.string.smart_suggestion_accepted, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.edit_suggestion_dismiss).setOnClickListener(v ->
                suggestionCard.setVisibility(View.GONE));
        MaterialButton delete = findViewById(R.id.edit_delete);
        delete.setVisibility(alarmId > 0 ? View.VISIBLE : View.GONE);
        delete.setOnClickListener(v -> delete());

        if (alarmId > 0) {
            loadExisting();
        } else {
            repeatGroup.check(R.id.repeat_workday_smart);
            shareToGroupSwitch.setChecked(true);
            challengeModeGroup.check(R.id.challenge_mode_off);
        }
        updateTimeLabel();
        updateVisibility();
    }

    private void openTimePicker() {
        // Roller (spinner) picker: two wheels, hour then minute — the familiar
        // scrolling interaction instead of typing digits.
        View content = getLayoutInflater().inflate(R.layout.dialog_time_picker, null);
        TimePicker picker = content.findViewById(R.id.time_picker);
        picker.setIs24HourView(true);
        picker.setHour(hour);
        picker.setMinute(minute);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_time_title)
                .setView(content)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) -> {
                    hour = picker.getHour();
                    minute = picker.getMinute();
                    updateTimeLabel();
                })
                .show();
    }

    private void updateTimeLabel() {
        timeButton.setText(String.format(Locale.CHINA, "%02d:%02d", hour, minute));
    }

    /** Keeps the snooze read-out in sync with the slider thumb. */
    private void renderSnoozeValue() {
        if (snoozeValue == null || snoozeSlider == null) {
            return;
        }
        snoozeValue.setText(getString(R.string.edit_snooze_value,
                (int) Math.round(snoozeSlider.getValue())));
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
            // Step 6.1: the suggestion is computed on the worker (Room read) but it
            // only rewrites the hour/minute fields when the user taps 采纳 — the
            // engine never writes to the database itself.
            final com.course.tongxiao.engine.adaptive.Suggestion suggestion;
            final int sampleDays;
            if (loaded != null) {
                java.util.List<com.course.tongxiao.data.entity.AlarmEvent> events =
                        db.alarmEventDao().getRecent(alarmId,
                                com.course.tongxiao.engine.adaptive.AdaptiveEngine
                                        .windowStartMillis(System.currentTimeMillis()));
                com.course.tongxiao.engine.adaptive.AdaptiveEngine engine =
                        new com.course.tongxiao.engine.adaptive.AdaptiveEngine();
                suggestion = engine.suggest(loaded, events);
                sampleDays = engine.sampleDays(loaded, events);
            } else {
                suggestion = null;
                sampleDays = 0;
            }
            // Weather hint: only meaningful when this alarm opted in, and it must
            // describe the day the alarm actually rings on (not always "tomorrow").
            final String weatherAdvanceText;
            if (loaded != null && loaded.advanceEnabled) {
                java.time.LocalDateTime next = calculator == null
                        ? null : calculator.nextTrigger(loaded, java.time.LocalDateTime.now());
                String day = next == null ? "" : formatNext(next);
                int advance = db.weatherCacheDao() == null ? 0
                        : new com.course.tongxiao.engine.weather.WeatherClient(db.weatherCacheDao())
                        .advanceMinutes(new com.course.tongxiao.data.SettingsStore(this).getCity(),
                                (next == null ? java.time.LocalDate.now().plusDays(1)
                                        : next.toLocalDate()).toString(), 20);
                weatherAdvanceText = advance <= 0 || day.isEmpty() ? null
                        : getString(R.string.smart_weather_hint, day, advance);
            } else {
                weatherAdvanceText = null;
            }

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
                renderSnoozeValue();
                vibrateSwitch.setChecked(loaded.vibrate);
                skipHolidaysSwitch.setChecked(loaded.skipHolidays);
                advanceWeatherSwitch.setChecked(loaded.advanceEnabled);
                shareToGroupSwitch.setChecked(loaded.sharedToGroup);
                checkChallengeMode(ChallengeConfig.fromJson(loaded.challengeOverrideJson));
                applyRepeatToUi(loaded);
                renderSuggestion(suggestion, sampleDays, weatherAdvanceText);
                this.loaded = true;
            });
        });
    }

    /** Shows the adaptive suggestion card and/or the weather advance hint. */
    private void renderSuggestion(
            @Nullable com.course.tongxiao.engine.adaptive.Suggestion suggestion,
            int sampleDays, @Nullable String weatherAdvanceText) {
        if (weatherAdvanceText != null) {
            weatherHint.setVisibility(View.VISIBLE);
            weatherHint.setText(weatherAdvanceText);
        } else {
            weatherHint.setVisibility(View.GONE);
        }
        boolean hasWeather = weatherAdvanceText != null;

        if (suggestion == null) {
            if (sampleDays > 0) {
                suggestionCard.setVisibility(View.VISIBLE);
                suggestionText.setVisibility(View.VISIBLE);
                suggestionTitle.setVisibility(View.VISIBLE);
                suggestionText.setText(getString(R.string.smart_suggestion_recording, sampleDays));
                suggestionActions.setVisibility(View.GONE);
            } else {
                suggestionCard.setVisibility(hasWeather ? View.VISIBLE : View.GONE);
                suggestionTitle.setVisibility(hasWeather ? View.GONE : View.VISIBLE);
                if (hasWeather) {
                    suggestionText.setVisibility(View.GONE);
                }
            }
            return;
        }

        pendingSuggestion = suggestion;
        suggestionCard.setVisibility(View.VISIBLE);
        suggestionTitle.setVisibility(View.VISIBLE);
        suggestionText.setVisibility(View.VISIBLE);
        suggestionActions.setVisibility(View.VISIBLE);
        String median = formatClock(suggestion.medianStopMinuteOfDay);
        String target = formatClock(suggestion.hour * 60 + suggestion.minute);
        suggestionText.setText(suggestion.type
                == com.course.tongxiao.engine.adaptive.Suggestion.Type.MOVE_EARLIER
                ? getString(R.string.smart_suggestion_earlier, suggestion.sampleDays, median, target)
                : getString(R.string.smart_suggestion_later, suggestion.sampleDays, median, target));
    }

    private static String formatClock(int minuteOfDay) {
        int safe = Math.max(0, Math.min(24 * 60 - 1, minuteOfDay));
        return String.format(Locale.CHINA, "%02d:%02d", safe / 60, safe % 60);
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

    /** Reflects a stored challenge config on the mode + question-source selectors. */
    private void checkChallengeMode(@NonNull ChallengeConfig config) {
        int id;
        switch (config.mode) {
            case QUIZ:
                id = R.id.challenge_mode_quiz;
                break;
            case SHAKE:
                id = R.id.challenge_mode_shake;
                break;
            case DIRECT:
            default:
                id = R.id.challenge_mode_off;
                break;
        }
        challengeModeGroup.check(id);
        updateQuestionSourceVisibility();
        questionSourceGroup.check(config.questionSource
                == ChallengeConfig.QuestionSource.MY_OWN
                ? R.id.question_source_mine : R.id.question_source_builtin);
    }

    /**
     * The 内置题库 / 我的题目 choice only means something for the multiple-choice mode;
     * it is hidden for 关闭 and 摇一摇 so the form never asks an irrelevant question.
     */
    private void updateQuestionSourceVisibility() {
        boolean quiz = challengeModeGroup.getCheckedButtonId() == R.id.challenge_mode_quiz;
        questionSourceLabel.setVisibility(quiz ? View.VISIBLE : View.GONE);
        questionSourceGroup.setVisibility(quiz ? View.VISIBLE : View.GONE);
        questionSourceHint.setVisibility(quiz ? View.VISIBLE : View.GONE);
        if (quiz) {
            int customCount = customQuestionStore.size();
            mineButton.setEnabled(customCount > 0);
            questionSourceHint.setText(customCount > 0
                    ? getString(R.string.edit_question_source_hint, customCount)
                    : getString(R.string.edit_question_source_none));
        }
    }

    /**
     * @return the JSON to persist for the selected challenge mode, or null when off
     *         (null means "inherit the app default", i.e. no challenge for this alarm)
     */
    @Nullable
    private String selectedChallengeJson() {
        int checked = challengeModeGroup.getCheckedButtonId();
        if (checked == R.id.challenge_mode_quiz) {
            ChallengeConfig config = ChallengeConfig.quizDefault();
            if (questionSourceGroup.getCheckedButtonId() == R.id.question_source_mine) {
                config = config.withQuestionSource(ChallengeConfig.QuestionSource.MY_OWN);
            }
            return config.toJson();
        }
        if (checked == R.id.challenge_mode_shake) {
            return ChallengeConfig.shakeDefault().toJson();
        }
        return null;
    }

    /** Builds an Alarm from the current form, reusing the loaded row when editing. */    private Alarm collectForm() {
        Alarm alarm = existing != null ? existing : new Alarm();
        alarm.hour = hour;
        alarm.minute = minute;
        alarm.label = labelInput.getText() == null ? "" : labelInput.getText().toString().trim();
        alarm.repeatMode = readRepeatMode();
        alarm.dayMask = readDayMask();
        alarm.skipHolidays = skipHolidaysSwitch.isChecked();
        alarm.snoozeMin = (int) snoozeSlider.getValue();
        alarm.vibrate = vibrateSwitch.isChecked();
        // Challenge is opt-in (design decision): null = inherit the app default.
        alarm.challengeOverrideJson = selectedChallengeJson();
        // Weather advance is opt-in per alarm (design.md §5.3 / §7②). This switch is
        // the only writer of these two fields; without it the scheduler's advance
        // branch could never trigger in production.
        alarm.advanceEnabled = advanceWeatherSwitch.isChecked();
        alarm.advanceMaxMin = alarm.advanceEnabled
                ? (alarm.advanceMaxMin > 0 ? alarm.advanceMaxMin : DEFAULT_ADVANCE_MAX_MIN)
                : 0;
        // Dorm sharing is the other half of the mute chain: only shared alarms reach
        // the roommates' mirror, and only mirrored alarms can be muted remotely.
        alarm.sharedToGroup = shareToGroupSwitch.isChecked();
        alarm.enabled = true; // saving an alarm implies enabling it
        return alarm;
    }

    private void save() {
        if (saving) {
            return; // double-tap on 保存 must not insert the same alarm twice
        }
        if (alarmId > 0 && !loaded) {
            // The row is still being read back; the form still holds defaults, so
            // saving now would INSERT a second alarm at 07:30 instead of updating.
            Toast.makeText(this, R.string.edit_loading_wait, Toast.LENGTH_SHORT).show();
            return;
        }
        Alarm.RepeatMode mode = readRepeatMode();
        if (mode == Alarm.RepeatMode.CUSTOM && readDayMask() == 0) {
            Toast.makeText(this, R.string.edit_need_day, Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        final Alarm alarm = collectForm();

        worker.execute(() -> {
            String when = null;
            try {
                if (alarm.id > 0) {
                    repository.update(alarm);
                } else {
                    alarm.id = (int) repository.insert(alarm);
                }
                long trigger = scheduler.schedule(alarm);
                if (trigger > 0) {
                    LocalDateTime next = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(trigger), ZoneId.systemDefault());
                    when = formatNext(next);
                }
            } catch (RuntimeException e) {
                // Persisted anyway: the boot/time-change sweep will re-arm it.
            }
            final String message = when == null
                    ? getString(R.string.edit_saved_no_time)
                    : getString(R.string.edit_saved_at, when);
            runOnUiThread(() -> {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    /**
     * Formats "今天 07:30" / "明天 07:30" / "周三 07:30" for the save confirmation.
     */
    private String formatNext(LocalDateTime next) {
        LocalDate today = LocalDate.now();
        String clock = String.format(Locale.CHINA, "%02d:%02d", next.getHour(), next.getMinute());
        if (next.toLocalDate().equals(today)) {
            return getString(R.string.next_today, clock);
        }
        if (next.toLocalDate().equals(today.plusDays(1))) {
            return getString(R.string.next_tomorrow, clock);
        }
        String[] week = getResources().getStringArray(R.array.weekday_short);
        int index = next.getDayOfWeek().getValue() - 1;
        return getString(R.string.next_weekday, week[index], clock);
    }

    private void delete() {
        final Alarm alarm = existing;
        if (alarm == null) {
            finish();
            return;
        }
        // Deleting also wipes the alarm's history (deleteWithEvents), so a mis-tap
        // next to 保存 would be unrecoverable: confirm first.
        String name = TextUtils.isEmpty(alarm.label)
                ? String.format(Locale.CHINA, "%02d:%02d", alarm.hour, alarm.minute)
                : alarm.label;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_delete_confirm_title)
                .setMessage(getString(R.string.edit_delete_confirm_text, name))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.edit_delete, (dialog, which) -> deleteAlarm(alarm))
                .show();
    }

    /**
     * Performs the deletion. Package-private so the UI test can exercise the
     * confirmed path without driving a system dialog.
     */
    void deleteAlarm(Alarm alarm) {
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
