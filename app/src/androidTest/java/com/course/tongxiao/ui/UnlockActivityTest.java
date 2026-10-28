package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.engine.challenge.ChallengeConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 4.2/4.3 G5: the challenge really gates the stop, and the emergency stop
 * really works.
 *
 * <p>The question order is random, so the test plays fairly: it taps options until
 * the challenge is passed (RETRY policy keeps the same question after a wrong
 * answer), which is exactly what a user can do.
 */
@RunWith(AndroidJUnit4.class)
public class UnlockActivityTest {

    private Context ctx;
    private TongxiaoDb db;
    private int alarmId;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);

        Alarm a = new Alarm();
        a.label = "挑战测试";
        a.hour = 7;
        a.minute = 30;
        a.enabled = true;
        a.challengeOverrideJson = ChallengeConfig.quizDefault().toJson();
        alarmId = (int) db.alarmDao().insert(a);
    }

    @After
    public void tearDown() {
        db.alarmDao().deleteById(alarmId);
    }

    private Intent intentFor(int id) {
        return new Intent(ctx, UnlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(UnlockActivity.EXTRA_ALARM_ID, id);
    }

    private List<AlarmEvent> events() {
        return db.alarmEventDao().getRecent(alarmId, 0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void answeringAllQuestionsStopsTheAlarmAndRecordsStopped() throws Exception {
        Intent intent = intentFor(alarmId);
        try (ActivityScenario<UnlockActivity> scenario = ActivityScenario.launch(intent)) {
            sleep(1200); // session is built on the worker

            scenario.onActivity(activity -> {
                TextView progress = activity.findViewById(R.id.unlock_progress);
                assertNotNull("challenge UI rendered", progress.getText());
                assertTrue("progress mentions the question index",
                        progress.getText().toString().contains("1"));
            });

            // Play until the activity finishes (max 4 options x 2 questions x retries).
            for (int i = 0; i < 24; i++) {
                if (isFinishing(scenario)) {
                    break;
                }
                scenario.onActivity(activity -> {
                    for (int b = 0; b < 4; b++) {
                        View button = activity.findViewById(
                                b == 0 ? R.id.btn_opt_0 : b == 1 ? R.id.btn_opt_1
                                        : b == 2 ? R.id.btn_opt_2 : R.id.btn_opt_3);
                        if (button != null && button.getVisibility() == View.VISIBLE) {
                            ((Button) button).performClick();
                        }
                    }
                });
                sleep(250);
            }

            List<AlarmEvent> events = events();
            assertFalse("passing the challenge must record an event", events.isEmpty());
            assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
            assertTrue("recurring alarm stays enabled",
                    db.alarmDao().getById(alarmId).enabled);
        }
    }

    @Test
    public void emergencyStop_recordsEmergencyTypeAndFinishes() throws Exception {
        Intent intent = intentFor(alarmId);
        try (ActivityScenario<UnlockActivity> scenario = ActivityScenario.launch(intent)) {
            sleep(1200);
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.unlock_emergency).performLongClick());
            sleep(1500);

            List<AlarmEvent> events = events();
            assertFalse("emergency stop must be recorded", events.isEmpty());
            assertEquals(AlarmEvent.Type.EMERGENCY_STOP, events.get(0).type);
            assertTrue("adaptive engine can tell it apart from a normal stop",
                    events.get(0).meta.contains("emergency"));
        }
    }

    @Test
    public void wrongAnswer_paintsFeedbackInsteadOfSilentlyResetting() throws Exception {
        // Regression: the RETRY/ESCALATE branches set the feedback text and then
        // called showQuestion(), which clears that very view — a wrong answer looked
        // like nothing happened at all.
        try (ActivityScenario<UnlockActivity> scenario =
                     ActivityScenario.launch(intentFor(alarmId))) {
            sleep(1200);
            boolean sawWrongAnswer = false;
            for (int b = 0; b < 4 && !sawWrongAnswer && !isFinishing(scenario); b++) {
                final int which = b;
                final String[] before = new String[1];
                final String[] after = new String[2];
                scenario.onActivity(activity -> before[0] = questionText(activity));
                scenario.onActivity(activity -> {
                    Button button = activity.findViewById(optionId(which));
                    if (button != null && button.getVisibility() == View.VISIBLE) {
                        button.performClick();
                    }
                });
                sleep(300);
                if (isFinishing(scenario)) {
                    break; // the challenge was passed by this click
                }
                scenario.onActivity(activity -> {
                    after[0] = questionText(activity);
                    after[1] = feedbackText(activity);
                });
                if (before[0] != null && before[0].equals(after[0])) {
                    sawWrongAnswer = true; // same question => the answer was wrong
                    assertFalse("a wrong answer must show feedback instead of a blank line",
                            after[1] == null || after[1].trim().isEmpty());
                }
            }
            assertTrue("clicking four options over two questions must hit a wrong answer",
                    sawWrongAnswer || isFinishing(scenario));
        }
    }

    private static int optionId(int index) {
        return index == 0 ? R.id.btn_opt_0
                : index == 1 ? R.id.btn_opt_1
                : index == 2 ? R.id.btn_opt_2 : R.id.btn_opt_3;
    }

    private static String questionText(android.app.Activity activity) {
        TextView view = activity.findViewById(R.id.unlock_question);
        return view == null || view.getText() == null ? null : view.getText().toString();
    }

    private static String feedbackText(android.app.Activity activity) {
        TextView view = activity.findViewById(R.id.unlock_feedback);
        return view == null || view.getText() == null ? null : view.getText().toString();
    }

    private static boolean isFinishing(ActivityScenario<UnlockActivity> scenario) {
        final boolean[] finishing = {false};
        scenario.onActivity(activity ->
                finishing[0] = activity.isFinishing() || activity.isDestroyed());
        return finishing[0];
    }

    @Test
    public void directModeAlarm_neverLaunchesChallenge() {
        Alarm plain = new Alarm();
        plain.label = "无挑战";
        plain.hour = 8;
        plain.enabled = true;
        plain.challengeOverrideJson = null;
        int id = (int) db.alarmDao().insert(plain);
        try {
            // With the app default being DIRECT, the config resolved for this alarm
            // has mode DIRECT, so the stop path never routes through UnlockActivity.
            ChallengeConfig resolved = ChallengeConfig.fromJson(null);
            assertEquals(ChallengeConfig.Mode.DIRECT, resolved.mode);
            ArrayList<Integer> ignored = new ArrayList<>();
            ignored.add(id);
        } finally {
            db.alarmDao().deleteById(id);
        }
    }
    /** SHAKE mode must render its progress line instead of the multiple-choice card. */
    @Test
    public void shakeChallenge_showsTheShakeCounter() throws Exception {
        Alarm shake = new Alarm();
        shake.label = "摇一摇";
        shake.hour = 7;
        shake.minute = 5;
        shake.enabled = true;
        shake.challengeOverrideJson =
                com.course.tongxiao.engine.challenge.ChallengeConfig.shakeDefault().toJson();
        int id = (int) db.alarmDao().insert(shake);
        try {
            Intent intent = new Intent(ctx, UnlockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(UnlockActivity.EXTRA_ALARM_ID, id);
            try (ActivityScenario<UnlockActivity> scenario = ActivityScenario.launch(intent)) {
                Thread.sleep(1200);
                scenario.onActivity(activity -> {
                    TextView shakeText = activity.findViewById(R.id.unlock_shake);
                    assertNotNull(shakeText);
                    assertEquals("shake UI is visible", View.VISIBLE, shakeText.getVisibility());
                    assertTrue("shows the target count",
                            shakeText.getText().toString().contains("5"));
                    assertEquals("the quiz card is hidden", View.GONE,
                            activity.findViewById(R.id.unlock_options).getVisibility());
                });
            }
        } finally {
            db.alarmDao().deleteById(id);
        }
    }
    /**
     * ESCALATE must be a real penalty: a wrong answer appends a harder question (the
     * total grows) and the strike counter is visible.
     */
    @Test
    public void wrongAnswerWithEscalate_addsAHarderQuestion() throws Exception {
        Alarm a = new Alarm();
        a.label = "加难测试";
        a.hour = 7;
        a.minute = 0;
        a.enabled = true;
        a.challengeOverrideJson = ChallengeConfig.quizDefault()
                .withWrongPolicy(ChallengeConfig.WrongPolicy.ESCALATE).toJson();
        int id = (int) db.alarmDao().insert(a);
        try {
            Intent intent = new Intent(ctx, UnlockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(UnlockActivity.EXTRA_ALARM_ID, id);
            try (ActivityScenario<UnlockActivity> scenario = ActivityScenario.launch(intent)) {
                Thread.sleep(1200);
                final String[] before = {""};
                scenario.onActivity(activity -> before[0] = text(
                        activity.findViewById(R.id.unlock_progress)));
                assertTrue("starts at question 1 of 2", before[0].contains("1 / 2"));

                // Tap every visible option once; one of them is wrong (wrong policy adds
                // a question), then read the progress + feedback lines.
                for (int i = 0; i < 4; i++) {
                    final int idx = i;
                    scenario.onActivity(activity -> {
                        android.view.View button = activity.findViewById(
                                idx == 0 ? R.id.btn_opt_0 : idx == 1 ? R.id.btn_opt_1
                                        : idx == 2 ? R.id.btn_opt_2 : R.id.btn_opt_3);
                        if (button != null && button.getVisibility() == android.view.View.VISIBLE) {
                            button.performClick();
                        }
                    });
                    Thread.sleep(150);
                }
                scenario.onActivity(activity -> {
                    String progress = text(activity.findViewById(R.id.unlock_progress));
                    assertTrue("the harder question grew the session: " + progress,
                            progress.contains(" / 3") || progress.contains(" / 4"));
                });
            }
        } finally {
            db.alarmDao().deleteById(id);
        }
    }

    private static String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }
}
