package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.RadioGroup;

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
}
