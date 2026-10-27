package com.course.tongxiao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Trigger entry point. Step 2.2 registers this as the AlarmManager target so the
 * scheduling chain is end-to-end testable; the real ring chain (foreground
 * service + full-screen activity) is implemented in Step 2.3.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmIntents.ACTION_FIRE_ALARM.equals(intent.getAction())) {
            return;
        }
        int alarmId = intent.getIntExtra(AlarmIntents.EXTRA_ALARM_ID, -1);
        String alarmUid = intent.getStringExtra(AlarmIntents.EXTRA_ALARM_UID);
        // NOTE: onReceive runs on the main thread; never touch Room inline here.
        // Step 2.3 replaces this log with startForegroundService(RingService).
        Log.i(TAG, "alarm fired: id=" + alarmId + " uid=" + alarmUid);
    }
}
