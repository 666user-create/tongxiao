package com.course.tongxiao.ui;

import androidx.annotation.NonNull;

/**
 * Which first-run permission banner (if any) the alarm list should show.
 *
 * <p>Pure decision logic on purpose: the real permission state cannot be toggled in
 * a test (revoking a runtime permission kills the process), so the policy lives here
 * and the fragment only supplies the three inputs.
 */
public final class PermissionBanner {

    public enum State {
        /** Nothing to tell the user. */
        NONE,
        /** Notifications are off: the ring will play but no lock-screen stop page. */
        NOTIFICATIONS,
        /** Exact alarms are off (Android 12/12L only): the alarm may be late. */
        EXACT_ALARMS
    }

    private PermissionBanner() {
    }

    /**
     * @param sdkInt              {@code Build.VERSION.SDK_INT}
     * @param notificationsGranted whether POST_NOTIFICATIONS is granted (true below 33)
     * @param exactAlarmsGranted   whether exact alarms are allowed (true outside 31..32)
     */
    @NonNull
    public static State resolve(int sdkInt, boolean notificationsGranted,
                                boolean exactAlarmsGranted) {
        // Notification permission exists from API 33; below that the setting is on.
        if (sdkInt >= 33 && !notificationsGranted) {
            return State.NOTIFICATIONS;
        }
        // SCHEDULE_EXACT_ALARM is user-revocable only on 12/12L; 13+ grants it.
        if (sdkInt >= 31 && sdkInt <= 32 && !exactAlarmsGranted) {
            return State.EXACT_ALARMS;
        }
        return State.NONE;
    }
}
