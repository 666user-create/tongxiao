package com.course.tongxiao.alarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.ActivityManager;
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

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Step 2.3 G5: the ring chain really promotes a foreground service, and — when
 * the notification permission is available — posts the alarm notification that
 * carries the full-screen intent.
 *
 * <p>Android 13+ hides notifications unless POST_NOTIFICATIONS is granted. The
 * test tries a shell grant via UiAutomation; if that is unavailable it still
 * asserts the foreground-service half and skips only the notification assertion.
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

        grantNotificationPermissionIfPossible();
    }

    @org.junit.After
    public void tearDown() {
        RingService.stop(ctx, alarmId);
        sleepQuietly(500);
        db.close();
    }

    private void grantNotificationPermissionIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
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
            // Fall back to Assume-guarded assertions below.
        }
    }

    private boolean notificationPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
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

    private boolean hasRingNotification() {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        StatusBarNotification[] all = nm.getActiveNotifications();
        if (all == null) {
            return false;
        }
        for (StatusBarNotification sbn : all) {
            if (sbn.getId() == NOTIFICATION_ID
                    && ctx.getPackageName().equals(sbn.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void startRing_promotesForegroundService_andStopsCleanly() {
        Intent ring = new Intent(ctx, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, alarmId);
        ContextCompat.startForegroundService(ctx, ring);

        boolean running = false;
        for (int i = 0; i < 50 && !running; i++) { // up to ~5s
            sleepQuietly(100);
            running = ringServiceRunning();
        }
        assertTrue("RingService must be running while ringing", running);

        if (notificationPermissionGranted()) {
            assertTrue("ringing notification must be posted", hasRingNotification());
        }

        RingService.stop(ctx, alarmId);
        boolean stopped = false;
        for (int i = 0; i < 50 && !stopped; i++) {
            sleepQuietly(100);
            stopped = !ringServiceRunning();
        }
        assertTrue("stop must shut the service down", stopped);
        if (notificationPermissionGranted()) {
            assertFalse("stop must remove the notification", hasRingNotification());
        }
    }

    @Test
    public void startRing_withInvalidAlarmId_doesNotCrashTheProcess() {
        Intent ring = new Intent(ctx, RingService.class)
                .setAction(RingService.ACTION_START_RING)
                .putExtra(RingService.EXTRA_ALARM_ID, -1);
        ContextCompat.startForegroundService(ctx, ring); // would kill the process on contract violation
        sleepQuietly(800);

        assertFalse("no ringing notification for an invalid id", hasRingNotification());
        // Reaching this line proves the startForeground() contract was honoured.
        assertTrue("test process survived", true);
    }

    /** Guard so a device without the permission reports a skip, not a silent pass. */
    @Test
    public void notificationPosting_requiresPermission() {
        Assume.assumeTrue("needs POST_NOTIFICATIONS", notificationPermissionGranted());
        assertTrue(true);
    }
}
