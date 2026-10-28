package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Step 2.3 G5 for the stop screen itself.
 *
 * <p>The system only surfaces StopRingActivity through the full-screen intent
 * when the device is locked or the screen is off (Android 10+ background-activity
 * rules), so this test launches the activity directly.
 *
 * <p>Uses the app-wide singleton database (that is what the activity uses) and
 * deliberately does NOT close it: closing it would break every later test class
 * in the same process ("connection pool has been closed"). Only this test's row
 * is cleaned up.
 */
@RunWith(AndroidJUnit4.class)
public class StopRingActivityTest {

    private Context ctx;
    private TongxiaoDb db;
    private int alarmId;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);

        Alarm a = new Alarm();
        a.label = "早八 · 教学楼A";
        a.hour = 7;
        a.minute = 30;
        a.enabled = true;
        a.snoozeMin = 10;
        alarmId = (int) db.alarmDao().insert(a);
    }

    @After
    public void tearDown() {
        db.alarmDao().deleteById(alarmId);
    }

    private Intent intentFor(int id) {
        Intent intent = new Intent(ctx, StopRingActivity.class);
        intent.putExtra(StopRingActivity.EXTRA_ALARM_ID, id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Test
    public void showsAlarmTimeAndLabel_thenStopSilencesAndRecordsEvent() throws Exception {
        try (ActivityScenario<StopRingActivity> scenario =
                     ActivityScenario.launch(intentFor(alarmId))) {

            scenario.onActivity(activity -> {
                TextView time = activity.findViewById(R.id.stop_time);
                TextView label = activity.findViewById(R.id.stop_label);
                assertEquals("07:30", time.getText().toString());
                assertEquals("早八 · 教学楼A", label.getText().toString());

                Button stop = activity.findViewById(R.id.btn_stop);
                assertTrue("stop button clickable", stop.isEnabled());
            });

            scenario.onActivity(activity -> activity.findViewById(R.id.btn_stop).performClick());
            Thread.sleep(1500); // stop runs on the activity's worker thread

            List<AlarmEvent> events = db.alarmEventDao().getRecent(alarmId, 0);
            assertFalse("a STOPPED event must be recorded", events.isEmpty());
            assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
            assertTrue("recurring alarm stays enabled",
                    db.alarmDao().getById(alarmId).enabled);
        }
    }

    @Test
    public void backPress_stopsTheAlarmInsteadOfLeavingItRinging() throws Exception {
        try (ActivityScenario<StopRingActivity> scenario =
                     ActivityScenario.launch(intentFor(alarmId))) {

            scenario.onActivity(Activity::onBackPressed);
            Thread.sleep(1500);

            List<AlarmEvent> events = db.alarmEventDao().getRecent(alarmId, 0);
            assertFalse("back press must still record STOPPED (ring silenced)",
                    events.isEmpty());
            assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
        }
    }

    @Test
    public void snooze_silencesWithoutRecordingStop_andKeepsOneShotArmed() throws Exception {
        // Regression (P1 漏响): 稍后提醒 used to silence through the STOP path, which
        // records STOPPED, disables a ONCE alarm and re-arms the regular schedule on
        // the same PendingIntent request code — wiping the snooze it had just armed,
        // so the user was never woken again.
        Alarm once = new Alarm();
        once.hour = 6;
        once.minute = 45;
        once.enabled = true;
        once.snoozeMin = 10;
        once.repeatMode = Alarm.RepeatMode.ONCE;
        int onceId = (int) db.alarmDao().insert(once);
        try (ActivityScenario<StopRingActivity> scenario =
                     ActivityScenario.launch(intentFor(onceId))) {

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.btn_snooze).performClick());
            Thread.sleep(1500);

            List<AlarmEvent> events = db.alarmEventDao().getRecent(onceId, 0);
            boolean snoozed = false;
            boolean stopped = false;
            for (AlarmEvent event : events) {
                snoozed |= event.type == AlarmEvent.Type.SNOOZE;
                stopped |= event.type == AlarmEvent.Type.STOPPED;
            }
            assertTrue("snooze must record a SNOOZE event", snoozed);
            assertFalse("snooze must not record a STOPPED event", stopped);
            assertTrue("snooze must not switch a one-shot alarm off",
                    db.alarmDao().getById(onceId).enabled);
        } finally {
            db.alarmEventDao().deleteByAlarm(onceId);
            db.alarmDao().deleteById(onceId);
        }
    }

    @Test
    public void missingAlarm_stillFinishesWithoutRingingForever() throws Exception {
        int ghostId = alarmId + 5000;
        try (ActivityScenario<StopRingActivity> scenario =
                     ActivityScenario.launch(intentFor(ghostId))) {
            Thread.sleep(1500);
            // Deliberately no onActivity(): the activity finishes itself, so the
            // assertion is that the scenario reached DESTROYED without crashing.
            assertEquals("activity must finish for a deleted alarm",
                    androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
        }
    }
}
