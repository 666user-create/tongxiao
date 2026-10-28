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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
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

    /**
     * Silences the ring without the "user stopped" bookkeeping.
     *
     * <p>Snooze must use this: {@link #ACTION_STOP_RING} records a STOPPED event and
     * re-arms the regular schedule (or disables a ONCE alarm), which overwrites the
     * snooze registration that was just made on the same PendingIntent request code —
     * the "10 分钟后" wake-up was silently lost that way.
     */
    public static final String EXTRA_SILENT_ONLY = "silent_only";

    private static final String CHANNEL_ID = "alarm_ringing";

    /** Upper bound for one ring (10 minutes): "宁可不响到没电". */
    private static final long MAX_RING_DURATION_MS = 10L * 60 * 1000;
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

    /** @return the ring channel id, so callers cannot hard-code a wrong one. */
    public static String channelId() {
        return CHANNEL_ID;
    }

    /** @return wall-clock time the current ring started, or 0. */
    public static long currentRingStartedAt() {
        return ringingStartedAt;
    }

    /**
     * Records the stop that came from the notification action. Runs on a worker so
     * the service is not blocked, and swallows failures: the ring is already silent
     * by then, and a bookkeeping error must not crash the process.
     */
    private void recordStoppedFromNotification() {
        final int id = alarmId > 0 ? alarmId : ringingAlarmId;
        final long startedAt = ringingStartedAt > 0 ? ringingStartedAt : ringStartedAt;
        if (id <= 0) {
            return;
        }
        final android.content.Context context = getApplicationContext();
        io.execute(() -> {
            try {
                TongxiaoDb db = TongxiaoDb.get(context);
                AlarmRepository repository = new AlarmRepository(db);
                HolidayResolver resolver = new HolidayResolver(
                        HolidayCalendar.loadFromAssets(context));
                AlarmScheduler scheduler = new AlarmScheduler(context, repository, resolver);
                Alarm alarm = db.alarmDao().getById(id);
                if (alarm != null) {
                    new RingActions(db, repository, scheduler)
                            .onStopped(alarm, startedAt, "user");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "stop bookkeeping from notification failed for " + id, e);
            }
        });
    }

    /** Fires once per ring; see MAX_RING_DURATION_MS. */
    private final Runnable ringTimeout = () -> {
        Log.w(TAG, "ring exceeded the maximum duration; stopping");
        recordTimeoutThenShutdown();
    };

    /** Bookkeeping executor: never blocks the service thread. */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();

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
            // The notification's 停止 action must do the SAME bookkeeping as the stop
            // screen: recording the event feeds the trend/adaptive samples, and for a
            // ONCE alarm it also switches the row off (otherwise a stopped one-shot
            // stays visible as "enabled" but never rings again — a zombie row).
            // Snooze silences the same way but owns its own re-arm, so it opts out.
            if (!intent.getBooleanExtra(EXTRA_SILENT_ONLY, false)) {
                recordStoppedFromNotification();
            }
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
        // Safety net: if neither the stop screen nor the notification is reachable
        // (notification permission denied, screen on and nobody taps), an alarm would
        // otherwise ring until the battery dies. Cap it.
        main.removeCallbacks(ringTimeout);
        main.postDelayed(ringTimeout, MAX_RING_DURATION_MS);
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
        ensureChannel(this);
    }

    /**
     * Creates the ring channel if it does not exist yet.
     *
     * <p>Public and static on purpose: {@code AlarmReceiver} posts a fallback
     * notification when starting the foreground service is refused, and posting to a
     * channel that was never created is dropped silently — the user would get no
     * notification at all on a fresh install where the service never started.
     */
    public static void ensureChannel(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ring_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.ring_channel_desc));
        channel.setSound(null, null); // sound comes from MediaPlayer, not the channel
        channel.enableVibration(false);
        // bypassDnd needs notification-policy access and would be silently ignored;
        // USAGE_ALARM is what actually carries the alarm through priority-only DND.
        nm.createNotificationChannel(channel);
    }

    private void recordTimeoutThenShutdown() {
        final int id = ringingAlarmId > 0 ? ringingAlarmId : alarmId;
        final long startedAt = ringingStartedAt > 0 ? ringingStartedAt : ringStartedAt;
        shutdown();
        if (id <= 0) {
            stopSelf();
            return;
        }
        final android.content.Context context = getApplicationContext();
        io.execute(() -> {
            try {
                TongxiaoDb db = TongxiaoDb.get(context);
                AlarmRepository repository = new AlarmRepository(db);
                AlarmScheduler scheduler = new AlarmScheduler(context, repository,
                        new HolidayResolver(HolidayCalendar.loadFromAssets(context)));
                Alarm alarm = db.alarmDao().getById(id);
                if (alarm != null) {
                    // Same state transition as a manual stop, with its own source so
                    // the stats can tell "unattended" apart from "got up".
                    new RingActions(db, repository, scheduler)
                            .onStopped(alarm, startedAt, "timeout");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "timeout bookkeeping failed for " + id, e);
            } finally {
                stopSelf();
            }
        });
    }

    /** Releases audio/vibration/wakelock and removes the notification. */
    private void shutdown() {
        main.removeCallbacks(ringTimeout);
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
        io.shutdown();
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

    /**
     * Silences the ring only: no STOPPED event, no re-arm, no ONCE disable.
     *
     * <p>Used by the snooze path, which does its own {@code onSnoozed} bookkeeping;
     * running the stop path as well would cancel the snooze it just scheduled.
     */
    public static void silence(Context context, int alarmIdValue) {
        Intent intent = new Intent(context, RingService.class)
                .setAction(ACTION_STOP_RING)
                .putExtra(EXTRA_ALARM_ID, alarmIdValue)
                .putExtra(EXTRA_SILENT_ONLY, true);
        context.startService(intent);
    }
}
