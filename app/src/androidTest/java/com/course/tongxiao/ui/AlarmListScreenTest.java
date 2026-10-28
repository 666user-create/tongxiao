package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Step 3.1 G2/G5: the list renders what Room holds, shows the holiday subtitle,
 * and the switch persists + re-arms.
 *
 * <p>Uses the app-wide singleton DB (that is what the activity reads) and only
 * removes its own rows afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmListScreenTest {

    private Context ctx;
    private TongxiaoDb db;
    private int firstId;
    private int secondId;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);

        Alarm a = new Alarm();
        a.label = "早八";
        a.hour = 7;
        a.minute = 30;
        a.enabled = true;
        a.repeatMode = Alarm.RepeatMode.WORKDAY_SMART;
        firstId = (int) db.alarmDao().insert(a);

        Alarm b = new Alarm();
        b.label = "跑步";
        b.hour = 6;
        b.minute = 40;
        b.enabled = true;
        b.repeatMode = Alarm.RepeatMode.DAILY;
        secondId = (int) db.alarmDao().insert(b);
    }

    @After
    public void tearDown() {
        db.alarmDao().deleteById(firstId);
        db.alarmDao().deleteById(secondId);
    }

    private int itemCount(ActivityScenario<MainActivity> scenario) {
        final int[] count = {0};
        scenario.onActivity(activity -> {
            RecyclerView rv = activity.findViewById(R.id.alarm_list);
            count[0] = rv.getAdapter() == null ? 0 : rv.getAdapter().getItemCount();
        });
        return count[0];
    }

    private void waitForItems(ActivityScenario<MainActivity> scenario, int expected)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (itemCount(scenario) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals("list did not reach the expected size", expected, itemCount(scenario));
    }

    @Test
    public void showsAlarmsSortedByTime_withHolidaySubtitle() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForItems(scenario, 2);

            scenario.onActivity(activity -> {
                RecyclerView rv = activity.findViewById(R.id.alarm_list);
                assertEquals(2, rv.getAdapter().getItemCount());

                // Repository orders by hour,minute → 06:40 first.
                View first = rv.getChildAt(0);
                TextView firstTime = first.findViewById(R.id.alarm_time);
                assertEquals("06:40", firstTime.getText().toString());

                View second = rv.getChildAt(1);
                assertEquals("07:30",
                        ((TextView) second.findViewById(R.id.alarm_time)).getText().toString());

                TextView subtitle = activity.findViewById(R.id.tomorrow_status);
                assertTrue("holiday subtitle must be filled",
                        subtitle.getText() != null && subtitle.getText().length() > 0);
                assertTrue("empty state hidden when alarms exist",
                        activity.findViewById(R.id.empty_state).getVisibility() == View.GONE);
            });
        }
    }

    @Test
    public void togglingSwitch_disablesAlarmInDatabase() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForItems(scenario, 2);

            final int targetId = secondId; // 06:40 row sits at position 0
            scenario.onActivity(activity -> {
                RecyclerView rv = activity.findViewById(R.id.alarm_list);
                View row = rv.getChildAt(0);
                // Identify the row by what it shows (getItem() is protected on ListAdapter).
                assertEquals("first row is the 06:40 alarm", "06:40",
                        ((TextView) row.findViewById(R.id.alarm_time)).getText().toString());
                row.findViewById(R.id.alarm_switch).performClick();
            });

            boolean disabled = false;
            for (int i = 0; i < 50 && !disabled; i++) {
                Thread.sleep(100);
                Alarm stored = db.alarmDao().getById(targetId);
                disabled = stored != null && !stored.enabled;
            }
            assertTrue("switch must persist enabled=false", disabled);
            assertTrue("other alarm untouched", db.alarmDao().getById(firstId).enabled);
            assertFalse("repository reports one enabled alarm",
                    db.alarmDao().getEnabled().isEmpty());

            // Regression: the ViewModel used to mutate the same Alarm instance the
            // adapter held, so DiffUtil reported "unchanged" and the row was never
            // rebound — it kept advertising a next wake-up for a switched-off alarm.
            String nextText = "?";
            for (int i = 0; i < 50 && !nextText.isEmpty(); i++) {
                final String[] read = new String[1];
                scenario.onActivity(activity -> {
                    RecyclerView rv = activity.findViewById(R.id.alarm_list);
                    View row = rv.getChildAt(0);
                    TextView next = row == null ? null : row.findViewById(R.id.alarm_next);
                    read[0] = next == null || next.getText() == null
                            ? null : next.getText().toString();
                });
                nextText = read[0] == null ? "" : read[0];
                if (nextText.isEmpty()) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals("a disabled alarm must not still show a next wake-up",
                    "", nextText);
        }
    }
    /**
     * Switching an alarm back on must actually re-register it.
     *
     * <p>The list adapter holds a snapshot of the row; after the "do not mutate the
     * adapter's object" fix, handing that stale object to the scheduler made it see
     * {@code enabled = false} and refuse to register — the switch looked on while the
     * alarm was never armed.
     */
    @Test
    public void enablingAnAlarm_registersItAgain() throws Exception {
        Alarm seed = new Alarm();
        seed.label = "重开测试";
        seed.hour = 9;
        seed.minute = 15;
        seed.enabled = false;
        final int id = (int) db.alarmDao().insert(seed);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForItems(scenario, 1);
            // Rows are ordered by time; pick the 09:15 one instead of the first switch
            // (toggling an already-on row would be a no-op and prove nothing).
            final android.widget.CompoundButton[] target = {null};
            scenario.onActivity(activity -> {
                RecyclerView rv = activity.findViewById(R.id.alarm_list);
                assertNotNull(rv);
                for (int i = 0; i < rv.getChildCount(); i++) {
                    View row = rv.getChildAt(i);
                    TextView time = row.findViewById(R.id.alarm_time);
                    if (time != null && "09:15".contentEquals(time.getText())) {
                        target[0] = row.findViewById(R.id.alarm_switch);
                    }
                }
            });
            assertNotNull("the seeded row is on screen", target[0]);
            scenario.onActivity(activity -> target[0].setChecked(true));
            Thread.sleep(1500);

            assertTrue("DB row is enabled", db.alarmDao().getById(id).enabled);
            assertTrue("AlarmManager really got the registration",
                    hasRegistration(db.alarmDao().getById(id)));
        } finally {
            db.alarmDao().deleteById(id);
        }
    }

    private boolean hasRegistration(Alarm alarm) {
        android.content.Intent intent = new android.content.Intent(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                com.course.tongxiao.alarm.AlarmReceiver.class)
                .setAction(com.course.tongxiao.alarm.AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(com.course.tongxiao.alarm.AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(com.course.tongxiao.alarm.AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        return android.app.PendingIntent.getBroadcast(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                alarm.id, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_NO_CREATE) != null;
    }
}
