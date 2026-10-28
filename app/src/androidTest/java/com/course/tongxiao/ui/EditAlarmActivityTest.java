package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.R;
import com.course.tongxiao.alarm.AlarmIntents;
import com.course.tongxiao.alarm.AlarmReceiver;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Step 3.2 G2/G5: the edit screen persists what the user filled in and re-arms. */
@RunWith(AndroidJUnit4.class)
public class EditAlarmActivityTest {

    private Context ctx;
    private TongxiaoDb db;
    private final java.util.ArrayList<Integer> created = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);
    }

    @After
    public void tearDown() {
        for (int id : created) {
            db.alarmDao().deleteById(id);
        }
    }

    private void saveViaButton(ActivityScenario<EditAlarmActivity> scenario) {
        scenario.onActivity(activity ->
                activity.findViewById(R.id.edit_save).performClick());
    }

    private boolean hasRegistration(Alarm alarm) {
        Intent intent = new Intent(ctx, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        return PendingIntent.getBroadcast(ctx, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE) != null;
    }

    @Test
    public void snoozeSlider_showsItsValue_nextToTheTitle() throws Exception {
        // Regression: the Material3 slider draws no label of its own, so the chosen
        // snooze interval was invisible — the read-out beside the title fixes that.
        String label = "滑杆测试-" + System.nanoTime();
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            final String[] initial = new String[1];
            final String[] moved = new String[1];

            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText(label);
                TextView value = activity.findViewById(R.id.edit_snooze_value);
                initial[0] = value == null ? null : value.getText().toString();
                ((com.google.android.material.slider.Slider)
                        activity.findViewById(R.id.edit_snooze)).setValue(25);
                moved[0] = value == null ? null : value.getText().toString();
            });
            assertNotNull("the snooze read-out exists", initial[0]);
            assertTrue("default interval is shown: '" + initial[0] + "'",
                    initial[0].contains("10"));
            assertTrue("dragging the slider updates the read-out: '" + moved[0] + "'",
                    moved[0].contains("25"));

            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm a : db.alarmDao().getAll()) {
                if (label.equals(a.label)) {
                    stored = a;
                }
            }
            assertNotNull("alarm persisted", stored);
            created.add(stored.id);
            assertEquals("the shown value is the value that gets stored", 25, stored.snoozeMin);
        }
    }

    @Test
    public void creatingDailyAlarm_persistsAndRegisters() throws Exception {
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("测试闹钟");
                ((RadioGroup) activity.findViewById(R.id.edit_repeat_group))
                        .check(R.id.repeat_daily);
            });
            saveViaButton(scenario);
            Thread.sleep(1500);

            List<Alarm> all = db.alarmDao().getAll();
            Alarm stored = null;
            for (Alarm a : all) {
                if ("测试闹钟".equals(a.label)) {
                    stored = a;
                }
            }
            assertNotNull("alarm persisted", stored);
            created.add(stored.id);
            assertEquals(Alarm.RepeatMode.DAILY, stored.repeatMode);
            assertTrue("saving enables the alarm", stored.enabled);
            assertTrue("alarm registered with AlarmManager", hasRegistration(stored));
        }
    }

    @Test
    public void editingExistingAlarm_updatesFieldsAndReregisters() throws Exception {
        Alarm seed = new Alarm();
        seed.label = "旧名字";
        seed.hour = 7;
        seed.minute = 0;
        seed.repeatMode = Alarm.RepeatMode.DAILY;
        seed.enabled = true;
        seed.id = (int) db.alarmDao().insert(seed);
        created.add(seed.id);

        Intent intent = new Intent(ctx, EditAlarmActivity.class)
                .putExtra(EditAlarmActivity.EXTRA_ALARM_ID, seed.id);
        try (ActivityScenario<EditAlarmActivity> scenario = ActivityScenario.launch(intent)) {
            Thread.sleep(800); // loadExisting() runs on the worker
            scenario.onActivity(activity ->
                    ((EditText) activity.findViewById(R.id.edit_label)).setText("新名字"));
            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = db.alarmDao().getById(seed.id);
            assertNotNull(stored);
            assertEquals("新名字", stored.label);
            assertTrue(hasRegistration(stored));
        }
    }

    @Test
    public void deleteFromMenu_removesRowAndRegistration() throws Exception {
        Alarm seed = new Alarm();
        seed.label = "待删除";
        seed.hour = 8;
        seed.minute = 0;
        seed.repeatMode = Alarm.RepeatMode.DAILY;
        seed.enabled = true;
        seed.id = (int) db.alarmDao().insert(seed);

        Intent intent = new Intent(ctx, EditAlarmActivity.class)
                .putExtra(EditAlarmActivity.EXTRA_ALARM_ID, seed.id);
        try (ActivityScenario<EditAlarmActivity> scenario = ActivityScenario.launch(intent)) {
            Thread.sleep(800);
            // Deleting asks for confirmation first: the history is wiped with the row,
            // so a mis-tap next to 保存 must not be destructive.
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.edit_delete).performClick());
            Thread.sleep(800);
            assertNotNull("tapping 删除 must NOT delete before confirmation",
                    db.alarmDao().getById(seed.id));

            // Confirmed path (the dialog's positive button calls exactly this).
            Alarm target = db.alarmDao().getById(seed.id);
            scenario.onActivity(activity -> activity.deleteAlarm(target));
            Thread.sleep(1500);

            assertNotNull("row deleted", db.alarmDao().getById(seed.id) == null ? "gone" : null);
            assertFalse("registration removed", hasRegistration(seed));
        }
    }
    /**
     * The edit screen must be able to produce a SHAKE challenge.
     *
     * <p>Before the three-way selector existed, only codes written by a peer or by
     * hand could reach shake mode, so that whole feature was unreachable from the UI.
     */
    @Test
    public void selectingShakeMode_persistsAShakeChallenge() throws Exception {
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            Thread.sleep(600);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("摇一摇闹钟");
                activity.findViewById(R.id.challenge_mode_shake).performClick();
            });
            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm alarm : db.alarmDao().getAll()) {
                if ("摇一摇闹钟".equals(alarm.label)) {
                    stored = alarm;
                }
            }
            assertNotNull("alarm persisted", stored);
            created.add(stored.id);
            assertNotNull("a challenge override was written", stored.challengeOverrideJson);
            assertEquals(com.course.tongxiao.engine.challenge.ChallengeConfig.Mode.SHAKE,
                    com.course.tongxiao.engine.challenge.ChallengeConfig
                            .fromJson(stored.challengeOverrideJson).mode);
        }
    }
    /**
     * Per-alarm question source: picking 我的题目 must persist MY_OWN in the alarm's
     * challenge JSON (and stay BUILT_IN for the default).
     */
    @Test
    public void choosingMyOwnQuestions_persistsTheQuestionSource() throws Exception {
        com.course.tongxiao.engine.challenge.CustomQuestionStore store =
                new com.course.tongxiao.engine.challenge.CustomQuestionStore(ctx);
        store.clear();
        store.add("自出题", java.util.Arrays.asList("A", "B", "C", "D"), 1);

        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            Thread.sleep(800);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("自出题闹钟");
                activity.findViewById(R.id.challenge_mode_quiz).performClick();
            });
            Thread.sleep(300);
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.question_source_mine).performClick());

            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm alarm : db.alarmDao().getAll()) {
                if ("自出题闹钟".equals(alarm.label)) {
                    stored = alarm;
                }
            }
            assertNotNull("alarm persisted", stored);
            created.add(stored.id);
            assertNotNull("challenge config written", stored.challengeOverrideJson);
            com.course.tongxiao.engine.challenge.ChallengeConfig config =
                    com.course.tongxiao.engine.challenge.ChallengeConfig
                            .fromJson(stored.challengeOverrideJson);
            assertEquals(com.course.tongxiao.engine.challenge.ChallengeConfig.Mode.QUIZ,
                    config.mode);
            assertEquals("per-alarm opt-in to own questions",
                    com.course.tongxiao.engine.challenge.ChallengeConfig.QuestionSource.MY_OWN,
                    config.questionSource);
        } finally {
            store.clear();
        }
    }
    /** The wrong-answer penalty is per alarm and must survive a save/load round trip. */
    @Test
    public void choosingACoolingPenalty_persistsTheWrongPolicy() throws Exception {
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            Thread.sleep(800);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("冷卻惩罚闹钟");
                activity.findViewById(R.id.challenge_mode_quiz).performClick();
            });
            Thread.sleep(300);
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.wrong_policy_cooldown).performClick());

            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm alarm : db.alarmDao().getAll()) {
                if ("冷卻惩罚闹钟".equals(alarm.label)) {
                    stored = alarm;
                }
            }
            assertNotNull("alarm persisted", stored);
            created.add(stored.id);
            assertNotNull(stored.challengeOverrideJson);
            assertEquals(com.course.tongxiao.engine.challenge.ChallengeConfig.WrongPolicy.ADD_SNOOZE,
                    com.course.tongxiao.engine.challenge.ChallengeConfig
                            .fromJson(stored.challengeOverrideJson).wrongPolicy);
        }
    }
    /** New alarms follow the system alarm sound: the row stays "默认闹钟铃声". */
    @Test
    public void newAlarm_keepsTheDefaultRingtone() throws Exception {
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            Thread.sleep(600);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("默认铃声闹钟");
                android.widget.Button ringtone = activity.findViewById(R.id.edit_ringtone);
                assertNotNull("ringtone row exists", ringtone);
                assertEquals("default label until the user picks a sound",
                        activity.getString(R.string.edit_ringtone_default),
                        String.valueOf(ringtone.getText()));
            });
            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm alarm : db.alarmDao().getAll()) {
                if ("默认铃声闹钟".equals(alarm.label)) {
                    stored = alarm;
                }
            }
            assertNotNull(stored);
            created.add(stored.id);
            assertNull("no override: the alarm follows the device setting",
                    stored.ringtoneUri);
        }
    }

    /** Editing an alarm with a chosen sound must keep that sound on save. */
    @Test
    public void editingAnAlarm_preservesTheChosenRingtone() throws Exception {
        Alarm seed = new Alarm();
        seed.label = "自定义铃声";
        seed.hour = 6;
        seed.minute = 15;
        seed.enabled = true;
        seed.ringtoneUri = "content://media/internal/audio/media/12345";
        seed.id = (int) db.alarmDao().insert(seed);

        Intent intent = new Intent(ctx, EditAlarmActivity.class)
                .putExtra(EditAlarmActivity.EXTRA_ALARM_ID, seed.id);
        try (ActivityScenario<EditAlarmActivity> scenario = ActivityScenario.launch(intent)) {
            Thread.sleep(1000);
            scenario.onActivity(activity -> {
                android.widget.Button ringtone = activity.findViewById(R.id.edit_ringtone);
                assertNotNull(ringtone);
                // Unknown/unreadable sounds fall back to a clear message, never a crash
                // and never the "default" label (the alarm is not on the default).
                assertFalse("must not claim to be the default",
                        activity.getString(R.string.edit_ringtone_default)
                                .contentEquals(ringtone.getText()));
            });
            saveViaButton(scenario);
            Thread.sleep(1500);

            assertEquals("the chosen sound survives the round trip",
                    "content://media/internal/audio/media/12345",
                    db.alarmDao().getById(seed.id).ringtoneUri);
        } finally {
            db.alarmDao().deleteById(seed.id);
        }
    }
    /** Picker results: "default" and "nothing" both mean "follow the device". */
    @Test
    public void pickedRingtone_isNormalizedToNullForTheSystemDefault() {
        android.net.Uri defaultAlarm = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM);
        android.net.Uri custom = android.net.Uri.parse(
                "content://media/internal/audio/media/777");

        assertNull("nothing picked", EditAlarmActivity.normalizePickedRingtone(null, defaultAlarm));
        assertNull("the default alarm uri means default",
                EditAlarmActivity.normalizePickedRingtone(defaultAlarm, defaultAlarm));
        assertEquals("a real sound is stored as-is", custom.toString(),
                EditAlarmActivity.normalizePickedRingtone(custom, defaultAlarm));
    }
    /** Vibration strength is per alarm and must survive a save/load round trip. */
    @Test
    public void selectingStrongVibration_persistsTheStrength() throws Exception {
        try (ActivityScenario<EditAlarmActivity> scenario =
                     ActivityScenario.launch(EditAlarmActivity.class)) {
            Thread.sleep(600);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.edit_label)).setText("强振动闹钟");
                activity.findViewById(R.id.vibrate_strong).performClick();
            });
            saveViaButton(scenario);
            Thread.sleep(1500);

            Alarm stored = null;
            for (Alarm alarm : db.alarmDao().getAll()) {
                if ("强振动闹钟".equals(alarm.label)) {
                    stored = alarm;
                }
            }
            assertNotNull(stored);
            created.add(stored.id);
            assertEquals(com.course.tongxiao.alarm.VibrationProfiles.STRONG,
                    stored.vibrateStrength);
            assertTrue("vibration itself stays on", stored.vibrate);
        }
    }
}
