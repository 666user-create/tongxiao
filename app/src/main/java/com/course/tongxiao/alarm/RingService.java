package com.course.tongxiao.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that actually rings (design.md §2.2, §6).
 *
 * <p>Why a foreground service: the ring must survive the activity being
 * backgrounded and the screen being off, and the notification (with a
 * full-screen intent) is what lets the system show StopRingActivity on top of
 * the keyguard on Android 10+.
 *
 * <p>Lifecycle discipline: every exit path releases player/vibrator/wakelock and
 * calls {@code stopForeground(STOP_FOREGROUND_REMOVE)} — a leftover notification
 * would otherwise keep the service "running" forever.
 */
public class RingService extends Service {

    private static final String TAG = "RingService";

    public static final String ACTION_START_RING = "com.course.tongxiao.action.START_RING_RING";
    public static final String ACTION_STOP_RING = "com.course.tongxiao.action.STOP_RING";

    public static final String EXTRA_ALARM_ID = AlarmIntents.EXTRA_ALARM_ID;

    private static final String CHANNEL_ID = "alarm_ringing";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private int alarmId = -1;
    private boolean ringing;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_RING.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);

        // Contract of startForegroundService(): startForeground() must be called
        // within ~5s even when the request turns out to be invalid, otherwise the
        // system throws RemoteServiceException and kills the process.
        startForeground(NOTIFICATION_ID, buildNotification(Math.max(alarmId, 0)));

        if (alarmId <= 0) {
            Log.w(TAG, "started without a valid alarm id");
            shutdown();
            return START_NOT_STICKY;
        }

        beginRinging();
        return START_STICKY;
    }

    private void beginRinging() {
        if (ringing) {
            return; // duplicate start while already ringing
        }
        ringing = true;
        acquireWakeLock();
        startPlayer();
        startVibrator();
        // Try to show the stop screen. On Android 10+ the full-screen-intent
        // notification above is the reliable path when the screen is locked.
        try {
            Intent stopUi = new Intent(this, StopRingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_ALARM_ID, alarmId);
            startActivity(stopUi);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not launch StopRingActivity directly", e);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tongxiao:ring");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(10 * 60 * 1000L); // safety cap: never hold forever
    }

    private void startPlayer() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (uri == null) {
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.e(TAG, "alarm sound failed", e);
            releasePlayer();
        }
    }

    private void startVibrator() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        long[] pattern = {0, 800, 600, 800, 600};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private Notification buildNotification(int alarmIdValue) {
        Intent stopUi = new Intent(this, StopRingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue);
        PendingIntent fullScreen = PendingIntent.getActivity(
                this, alarmIdValue, stopUi,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.ring_notification_title))
                .setContentText(getString(R.string.ring_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(content)
                .setFullScreenIntent(fullScreen, true)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ring_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.ring_channel_desc));
        channel.setSound(null, null); // sound comes from MediaPlayer, not the channel
        channel.enableVibration(false);
        channel.setBypassDnd(true); // an alarm must ring through Do Not Disturb
        nm.createNotificationChannel(channel);
    }

    /** Releases audio/vibration/wakelock and removes the notification. */
    private void shutdown() {
        ringing = false;
        releasePlayer();
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        // Belt and braces: stopForeground does not always remove an already-posted
        // notification (e.g. when the stop command reaches a fresh service instance).
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            player.release();
            player = null;
        }
    }

    @Override
    public void onDestroy() {
        ringing = false;
        releasePlayer();
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service only
    }

    /** Convenience for the stop screen. */
    public static void stop(Context context, int alarmIdValue) {
        Intent intent = new Intent(context, RingService.class)
                .setAction(ACTION_STOP_RING)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue);
        context.startService(intent);
    }
}
