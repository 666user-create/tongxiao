package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Policy for the first-run permission banner. The decision is pure so it can be
 * tested here: toggling the real POST_NOTIFICATIONS permission would kill the
 * instrumentation process.
 */
@RunWith(AndroidJUnit4.class)
public class PermissionBannerTest {

    private static final int API_26 = 26;
    private static final int API_30 = 30;
    private static final int API_31 = 31; // Android 12: SCHEDULE_EXACT_ALARM revocable
    private static final int API_32 = 32; // 12L
    private static final int API_33 = 33; // POST_NOTIFICATIONS + auto exact alarms
    private static final int API_36 = 36;

    @Test
    public void modernDeviceWithoutNotificationPermission_warnsAboutNotifications() {
        assertEquals(PermissionBanner.State.NOTIFICATIONS,
                PermissionBanner.resolve(API_33, false, true));
        assertEquals(PermissionBanner.State.NOTIFICATIONS,
                PermissionBanner.resolve(API_36, false, true));
    }

    @Test
    public void modernDeviceWithEverythingGranted_showsNothing() {
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_33, true, true));
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_36, true, true));
    }

    @Test
    public void android12WithoutExactAlarms_warnsAboutLateAlarms() {
        assertEquals(PermissionBanner.State.EXACT_ALARMS,
                PermissionBanner.resolve(API_31, true, false));
        assertEquals(PermissionBanner.State.EXACT_ALARMS,
                PermissionBanner.resolve(API_32, true, false));
    }

    @Test
    public void notificationPermission_isNotRequiredBelowApi33() {
        // Below 33 the permission does not exist: a "not granted" flag must not warn.
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_30, false, true));
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_26, false, true));
    }

    @Test
    public void onAndroid12_theExactAlarmMessageWins() {
        // Android 12/12L have no POST_NOTIFICATIONS permission at all, so the only
        // meaningful warning there is the exact-alarm one (one banner, not a stack).
        assertEquals(PermissionBanner.State.EXACT_ALARMS,
                PermissionBanner.resolve(API_31, true, false));
        assertEquals(PermissionBanner.State.EXACT_ALARMS,
                PermissionBanner.resolve(API_31, false, false));
    }

    @Test
    public void exactAlarmFlag_isIgnoredOutsideAndroid12() {
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_36, true, false));
        assertEquals(PermissionBanner.State.NONE,
                PermissionBanner.resolve(API_26, true, false));
    }
}
