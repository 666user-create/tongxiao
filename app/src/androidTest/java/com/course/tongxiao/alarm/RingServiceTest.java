package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;

import androidx.core.content.ContextCompat;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Step 2.3 G5: the ring chain promotes a foreground service and posts the alarm
 * notification that carries the full-screen intent and the Stop action.
 *
 * <p>The notification assertions run against the real NotificationManager; when
 * POST_NOTIFICATIONS cannot be granted the test fails instead of silently
 * skipping, because "notification posted" is the behaviour under test.
 */
@RunWith(AndroidJUnit4.class)
public class RingServiceTest {

    private static final int NOTIFICATION_ID = 1001;

    private Context ctx;
    private TongxiaoDb db;
    private int alarmId;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();

        Alarm a = new Alarm();
        a.label = "响铃测试";
        a.hour = 7;
        a.minute = 30;
        a.enabled = true;
        alarmId = (int) new AlarmRepository(db).insert(a);

        grantNotificationPermission();
    }

    @After
    public void tearDown() {
        RingService.stop(ctx, alarmId);
        sleepQuietly(500);
        db.close();
    }

    /** Tries `pm grant` as shell; fails the test if the permission is still missing. */
    private void grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .executeShellCommand("pm grant " + ctx.getPackageName()
                            + " android.permission.POST_NOTIFICATIONS");
            if (pfd != null) {
                pfd.close();
            }
            sleepQuietly(300);
        } catch (Exception ignored) {
            // asserted below
        }
        assertTrue("POST_NOTIFICATIONS must be granted for this test",
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean ringServiceRunning() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(50);
        for (ActivityManager.RunningServiceInfo info : services) {
            if (RingService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private StatusBarNotification ringNotification() {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        StatusBarNotification[] all = nm.getActiveNotifications();
        if (all == null) {
            return null;
        }
        for (StatusBarNotification sbn : all) {
            if (sbn.getId() == NOTIFICATION_ID
                    && ctx.getPackageName().equals(sbn.getPackageName())) {
                return sbn;
            }
        }
        return null;
    }

    private void startRing() {
        Intent ring = new Intent(ctx, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, alarmId);
        ContextCompat.startForegroundService(ctx, ring);
    }

    @Test
    public void startRing_promotesForegroundService_withFullScreenIntentAndStopAction() {
        startRing();

        boolean running = false;
        StatusBarNotification sbn = null;
        for (int i = 0; i < 50 && (sbn == null || !running); i++) { // up to ~5s
            sleepQuietly(100);
            running = ringServiceRunning();
            sbn = ringNotification();
        }
        assertTrue("RingService must be running while ringing", running);
        assertTrue("ringing notification must be posted", sbn != null);

        Notification n = sbn.getNotification();
        assertTrue("full-screen intent is what shows the stop screen on the keyguard",
                n.fullScreenIntent != null);
        assertTrue("notification must offer a Stop action (silence without the UI)",
                n.actions != null && n.actions.length >= 1);
        assertEquals("notification uses the alarm category",
                Notification.CATEGORY_ALARM, n.category);

        RingService.stop(ctx, alarmId);
        boolean stopped = false;
        for (int i = 0; i < 50 && !stopped; i++) {
            sleepQuietly(100);
            stopped = !ringServiceRunning() && ringNotification() == null;
        }
        assertTrue("stop must shut the service down and remove the notification", stopped);
    }

    @Test
    public void startRing_withInvalidAlarmId_doesNotCrashTheProcess() {
        Intent ring = new Intent(ctx, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, -1);
        ContextCompat.startForegroundService(ctx, ring); // would kill the process on contract violation
        sleepQuietly(1200);

        assertFalse("no ringing notification for an invalid id", ringNotification() != null);
        // Reaching this line proves the startForeground() contract was honoured;
        // a violation would have killed the process before the assertion ran.
    }
}
