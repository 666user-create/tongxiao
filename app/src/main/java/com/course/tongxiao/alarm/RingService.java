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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that actually rings (design.md 鎼?.2, 鎼?).
 *
 * <p>Why a foreground service: the ring must survive the activity being
 * backgrounded and the screen being off; the notification also carries the
 * full-screen intent the system shows on top of the keyguard.
 *
 * <p>Every exit path releases player/vibrator/wakelock and removes the
 * notification 閳?a leftover notification would keep the service "running".
 * The notification always offers a Stop action so the ring can be silenced even
 * if the stop screen is unreachable.
 */
public class RingService extends Service {

    private static final String TAG = "RingService";

    public static final String ACTION_START_RING = "com.course.tongxiao.action.START_RING";
    public static final String ACTION_STOP_RING = "com.course.tongxiao.action.STOP_RING";

    public static final String EXTRA_ALARM_ID = AlarmIntents.EXTRA_ALARM_ID;

    private static final String CHANNEL_ID = "alarm_ringing";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REQ_STOP_ACTION = 2001;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private int alarmId = -1;
    private long ringStartedAt;
    private boolean ringing;
    private boolean destroyed;

    /**
     * Which alarm is ringing right now, process-wide. The dorm group needs this to
     * answer CMD_MUTE (design.md §3.2: muting the ringing alarm needs no
     * confirmation) without the group package touching the service internals.
     */
    private static volatile String ringingAlarmUid;
    private static volatile long ringingStartedAt;

    /**
     * Row id known synchronously when the start command arrives, before the DB read
     * resolves the uid. Remote mute uses it as a fallback so a mute that lands in
     * that few-milliseconds window still matches the ringing alarm.
     */
    private static volatile int ringingAlarmId = -1;

    /** @return uid of the alarm currently ringing, or null. */
    @Nullable
    public static String currentRingingUid() {
        return ringingAlarmUid;
    }

    /** @return row id of the alarm currently ringing, or -1. */
    public static int currentRingingAlarmId() {
        return ringingAlarmId;
    }

    /** @return wall-clock time the current ring started, or 0. */
    public static long currentRingStartedAt() {
        return ringingStartedAt;
    }

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
        ringingAlarmId = alarmId;

        // Contract of startForegroundService(): startForeground() must happen
        // within ~5s even when the request turns out invalid, otherwise the
        // system throws RemoteServiceException and kills the process.
        startForeground(NOTIFICATION_ID, buildNotification(alarmId, "", ringStartedAt()));

        if (alarmId <= 0) {
            Log.w(TAG, "started without a valid alarm id");
            shutdown();
            return START_NOT_STICKY;
        }

        loadAlarmThenRing(alarmId);
        return START_STICKY;
    }

    private long ringStartedAt() {
        if (ringStartedAt == 0) {
            ringStartedAt = System.currentTimeMillis();
        }
        return ringStartedAt;
    }

    /**
     * Reads the alarm off the main thread, then rings with its own ringtone,
     * vibration choice and label. Missing alarm (deleted while ringing) falls
     * back to the default ringtone rather than staying silent.
     */
    private void loadAlarmThenRing(final int id) {
        worker.execute(() -> {
            Alarm alarm = null;
            try {
                alarm = TongxiaoDb.get(this).alarmDao().getById(id);
            } catch (RuntimeException e) {
                Log.e(TAG, "could not load alarm " + id, e);
            }
            final Alarm loaded = alarm;
            main.post(() -> applyAlarmAndRing(loaded, id));
        });
    }

    private void applyAlarmAndRing(@Nullable Alarm alarm, int id) {
        if (destroyed) {
            return;
        }
        String label = alarm == null || TextUtils.isEmpty(alarm.label)
                ? getString(R.string.alarm_default_label) : alarm.label;
        // Refresh the notification so it carries the alarm's own label.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(id, label, ringStartedAt()));
        }

        if (ringing) {
            return; // duplicate start while already ringing
        }
        ringing = true;
        ringingAlarmUid = alarm == null ? null : alarm.uid;
        ringingStartedAt = ringStartedAt();
        acquireWakeLock();
        startPlayer(alarm == null ? null : alarm.ringtoneUri);
        if (alarm == null || alarm.vibrate) {
            startVibrator();
        }
        launchStopScreen(label);
    }

    private void launchStopScreen(String label) {
        // Android 10+ blocks background activity launches; when the screen is
        // locked/off the full-screen intent notification is what the system uses.
        // Screen-on + unlocked simply shows the heads-up notification instead.
        Intent stopUi = new Intent(this, StopRingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmIntents.EXTRA_RING_STARTED_AT, ringStartedAt())
                .putExtra(StopRingActivity.EXTRA_LABEL, label);
        try {
            startActivity(stopUi);
        } catch (RuntimeException e) {
            Log.w(TAG, "direct activity start refused (expected on Android 10+ background)", e);
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

    private void startPlayer(@Nullable String ringtoneUri) {
        Uri uri = null;
        if (!TextUtils.isEmpty(ringtoneUri)) {
            uri = Uri.parse(ringtoneUri);
        }
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        }
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
            // prepareAsync: prepare() would block the main thread on a provider binder.
            player.setOnPreparedListener(mp -> {
                if (mp == player) {
                    mp.start();
                }
            });
            player.prepareAsync();
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
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
    }

    private Notification buildNotification(int alarmIdValue, String label, long startedAt) {
        Intent stopUi = new Intent(this, StopRingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue)
                .putExtra(AlarmIntents.EXTRA_RING_STARTED_AT, startedAt)
                .putExtra(StopRingActivity.EXTRA_LABEL, label);
        PendingIntent fullScreen = PendingIntent.getActivity(
                this, alarmIdValue, stopUi,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Stop action: getService() is not subject to background-activity limits,
        // so this silences the alarm even if the stop screen never came up.
        Intent stopService = new Intent(this, RingService.class)
                .setAction(ACTION_STOP_RING)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue);
        PendingIntent stopAction = PendingIntent.getService(
                this, REQ_STOP_ACTION, stopService,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(TextUtils.isEmpty(label)
                        ? getString(R.string.ring_notification_title)
                        : label)
                .setContentText(getString(R.string.ring_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(content)
                .setFullScreenIntent(fullScreen, true)
                .addAction(0, getString(R.string.stop_ring), stopAction)
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
        // bypassDnd needs notification-policy access and would be silently ignored;
        // USAGE_ALARM is what actually carries the alarm through priority-only DND.
        nm.createNotificationChannel(channel);
    }

    /** Releases audio/vibration/wakelock and removes the notification. */
    private void shutdown() {
        ringing = false;
        ringingAlarmUid = null;
        ringingAlarmId = -1;
        ringingStartedAt = 0L;
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
        // stopForeground does not always remove an already-posted notification
        // (e.g. the stop command reaching a fresh service instance).
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
        destroyed = true;
        ringing = false;
        ringingAlarmUid = null;
        ringingAlarmId = -1;
        ringingStartedAt = 0L;
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

    /** Convenience for the stop screen / notification action. */
    public static void stop(Context context, int alarmIdValue) {
        Intent intent = new Intent(context, RingService.class)
                .setAction(ACTION_STOP_RING)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue);
        // startService from a visible UI is fine; the notification action uses
        // PendingIntent.getService() and is not affected by background limits.
        context.startService(intent);
    }
}
