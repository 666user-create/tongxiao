package com.course.tongxiao.alarm;

import androidx.annotation.NonNull;

/**
 * Vibration patterns per strength (design.md §5.5).
 *
 * <p>Android exposes no "vibration intensity" setting an app can scale, so strength is
 * expressed the only way the platform allows: <b>amplitude</b> (0..255 on devices with
 * amplitude control) plus a slightly different <b>rhythm</b>, so even a motor without
 * amplitude control still feels different between light and strong.
 *
 * <p>Pure data on purpose: the alarm's ring path must not be the only thing that knows
 * what "strong" means, and this mapping is unit-tested.
 */
public final class VibrationProfiles {

    public static final int LIGHT = 0;
    public static final int MEDIUM = 1;
    public static final int STRONG = 2;

    private VibrationProfiles() {
    }

    /** Clamps any stored value to a known strength (unknown → medium). */
    public static int clampStrength(int strength) {
        if (strength < LIGHT || strength > STRONG) {
            return MEDIUM;
        }
        return strength;
    }

    /** @return {delay, on, off, on, off, ...} in milliseconds, repeating. */
    @NonNull
    public static long[] timings(int strength) {
        switch (clampStrength(strength)) {
            case LIGHT:
                return new long[]{0, 200, 800};
            case STRONG:
                return new long[]{0, 500, 150};
            case MEDIUM:
            default:
                return new long[]{0, 350, 400};
        }
    }

    /** @return amplitude per segment, or null when the device has no amplitude control. */
    @NonNull
    public static int[] amplitudes(int strength) {
        switch (clampStrength(strength)) {
            case LIGHT:
                return new int[]{0, 60, 0};
            case STRONG:
                return new int[]{0, 255, 0};
            case MEDIUM:
            default:
                return new int[]{0, 140, 0};
        }
    }

    /** A single short pulse used to preview the strength in the edit screen. */
    @NonNull
    public static long[] previewTimings() {
        return new long[]{0, 250};
    }

    public static int previewAmplitude(int strength) {
        return amplitudes(strength)[1];
    }
}
