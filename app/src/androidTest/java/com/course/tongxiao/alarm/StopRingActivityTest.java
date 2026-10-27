package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Step 2.3 G5 for the stop screen itself.
 *
 * <p>The system only surfaces StopRingActivity through the full-screen intent
 * when the device is locked or the screen is off (Android 10+ background-activity
 * rules), so this test launches the activity directly and verifies what it shows
 * and what stopping it records.
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
        db.close();
    }

    private Intent intentFor(int id) {
        Intent intent = new Intent(ctx, StopRingActivity.class);
        intent.putExtra(StopRingActivity.EXTRA_ALARM_ID, id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Test
    public void showsAlarmTimeAndLabel_thenStopRecordsEvent() throws Exception {
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

            // Visual evidence for the acceptance record (pulled while the run is alive).
            captureScreenshot("stop-screen.png");

            scenario.onActivity(activity -> activity.findViewById(R.id.btn_stop).performClick());
            Thread.sleep(1500); // stop runs on the activity's worker thread

            List<AlarmEvent> events = db.alarmEventDao().getRecent(alarmId, 0);
            assertFalse("a STOPPED event must be recorded", events.isEmpty());
            assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
            assertTrue("recurring alarm stays enabled",
                    db.alarmDao().getById(alarmId).enabled);
        }
    }

    private void captureScreenshot(String name) {
        try {
            Bitmap shot = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().takeScreenshot();
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (shot != null && dir != null) {
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                Thread.sleep(3000); // window for the external adb pull
            }
        } catch (Exception ignored) {
            // Screenshot is evidence only; never fail the behavioural assertions.
        }
    }
}
