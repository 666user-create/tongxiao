package com.course.tongxiao.alarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;

/**
 * Step 2.4 G5: system events rebuild the schedule.
 *
 * <p>TIME_SET / TIMEZONE_CHANGED are <b>protected broadcasts</b> — an app cannot
 * send them (SecurityException), and BOOT_COMPLETED cannot be simulated either.
 * So the test drives the receiver directly (its job is "on this action, call
 * rescheduleAll()") and separately asserts the manifest really subscribes to the
 * four actions, which is what the system will deliver in production.
 */
@RunWith(AndroidJUnit4.class)
public class SystemEventReceiverTest {

    private Context ctx;
    private TongxiaoDb db;
    private AlarmScheduler scheduler;
    private int alarmId;
    private Alarm alarm;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(ctx));
        scheduler = new AlarmScheduler(ctx, new AlarmRepository(db), resolver);

        LocalDateTime t = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        alarm = new Alarm();
        alarm.label = "恢复测试";
        alarm.repeatMode = Alarm.RepeatMode.DAILY;
        alarm.hour = t.getHour();
        alarm.minute = t.getMinute();
        alarm.enabled = true;
        alarmId = (int) db.alarmDao().insert(alarm);
        alarm.id = alarmId;
    }

    @org.junit.After
    public void tearDown() {
        scheduler.cancel(alarm);
        db.alarmDao().deleteById(alarmId);
    }

    private boolean hasRegistration() {
        Intent intent = new Intent(ctx, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        return android.app.PendingIntent.getBroadcast(ctx, alarmId, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_NO_CREATE) != null;
    }

    private void deliverAndWait(String action) throws InterruptedException {
        SystemEventReceiver receiver = new SystemEventReceiver();
        receiver.onReceive(ctx, new Intent(action));
        for (int i = 0; i < 60; i++) { // receiver does the sweep on an executor
            Thread.sleep(100);
            if (hasRegistration()) {
                return;
            }
        }
    }

    @Test
    public void timeChanged_rearmsCancelledRegistration() throws Exception {
        scheduler.schedule(alarm);
        scheduler.cancel(alarm);
        assertFalse("cancelled before the event", hasRegistration());

        deliverAndWait(Intent.ACTION_TIME_CHANGED);

        assertTrue("TIME_SET must trigger rescheduleAll and re-arm the alarm",
                hasRegistration());
    }

    @Test
    public void timezoneChanged_rearmsCancelledRegistration() throws Exception {
        scheduler.schedule(alarm);
        scheduler.cancel(alarm);
        assertFalse("cancelled before the event", hasRegistration());

        deliverAndWait(Intent.ACTION_TIMEZONE_CHANGED);

        assertTrue("TIMEZONE_CHANGED must re-arm the alarm", hasRegistration());
    }

    @Test
    public void bootCompleted_rearmsCancelledRegistration() throws Exception {
        scheduler.schedule(alarm);
        scheduler.cancel(alarm);
        assertFalse("cancelled before the event", hasRegistration());

        deliverAndWait(Intent.ACTION_BOOT_COMPLETED);

        assertTrue("BOOT_COMPLETED must re-arm the alarm", hasRegistration());
    }

    @Test
    public void disabledAlarm_isNotRescheduled() throws Exception {
        scheduler.schedule(alarm);
        assertTrue(hasRegistration());

        db.alarmDao().setEnabled(alarmId, false);
        deliverAndWait(Intent.ACTION_TIME_CHANGED);

        assertFalse("a disabled alarm must not be re-armed by the sweep", hasRegistration());
    }

    @Test
    public void manifest_subscribesToAllFourActions() throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_RECEIVERS);
        boolean declared = false;
        for (android.content.pm.ActivityInfo receiver : info.receivers) {
            if (SystemEventReceiver.class.getName().equals(receiver.name)) {
                declared = true;
                assertFalse("receiver must not be exported", receiver.exported);
            }
        }
        assertTrue("SystemEventReceiver declared in manifest", declared);

        // Each protected action must resolve to our receiver inside our package.
        String[] actions = {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
        };
        for (String action : actions) {
            Intent probe = new Intent(action).setPackage(ctx.getPackageName());
            boolean resolved = false;
            for (android.content.pm.ResolveInfo ri : pm.queryBroadcastReceivers(probe, 0)) {
                if (ri.activityInfo != null
                        && SystemEventReceiver.class.getName().equals(ri.activityInfo.name)) {
                    resolved = true;
                }
            }
            assertTrue(action + " must be routed to SystemEventReceiver", resolved);
        }
    }
}
