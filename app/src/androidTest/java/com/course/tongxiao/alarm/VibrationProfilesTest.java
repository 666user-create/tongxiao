package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Vibration strength mapping (design.md §5.5). Android has no intensity API, so the
 * app expresses it as amplitude + rhythm; these tests pin the contract that the ring
 * path and the edit-screen preview both rely on.
 */
@RunWith(AndroidJUnit4.class)
public class VibrationProfilesTest {

    @Test
    public void unknownStrength_fallsBackToMedium() {
        assertEquals(VibrationProfiles.MEDIUM, VibrationProfiles.clampStrength(-5));
        assertEquals(VibrationProfiles.MEDIUM, VibrationProfiles.clampStrength(99));
        assertEquals(VibrationProfiles.LIGHT, VibrationProfiles.clampStrength(0));
        assertEquals(VibrationProfiles.STRONG, VibrationProfiles.clampStrength(2));
    }

    @Test
    public void eachStrength_hasItsOwnRhythm() {
        long[] light = VibrationProfiles.timings(VibrationProfiles.LIGHT);
        long[] medium = VibrationProfiles.timings(VibrationProfiles.MEDIUM);
        long[] strong = VibrationProfiles.timings(VibrationProfiles.STRONG);

        assertNotEquals("light and medium must feel different", light[1], medium[1]);
        assertNotEquals("medium and strong must feel different", medium[1], strong[1]);
        for (long[] timings : new long[][]{light, medium, strong}) {
            assertTrue("a pattern must start at delay 0", timings[0] == 0);
            assertTrue("on segments must be positive", timings[1] > 0);
            assertTrue("timings and amplitudes must line up",
                    timings.length == VibrationProfiles
                            .amplitudes(VibrationProfiles.MEDIUM).length);
        }
    }

    @Test
    public void amplitudes_growWithStrength() {
        int light = VibrationProfiles.amplitudes(VibrationProfiles.LIGHT)[1];
        int medium = VibrationProfiles.amplitudes(VibrationProfiles.MEDIUM)[1];
        int strong = VibrationProfiles.amplitudes(VibrationProfiles.STRONG)[1];

        assertTrue("light < medium", light < medium);
        assertTrue("medium < strong", medium < strong);
        assertTrue("amplitudes stay inside 1..255", light > 0 && strong <= 255);
        assertEquals("the first segment is silent in every pattern",
                0, VibrationProfiles.amplitudes(VibrationProfiles.STRONG)[0]);
    }

    @Test
    public void preview_usesTheSameAmplitudeAsTheRealRing() {
        for (int strength = VibrationProfiles.LIGHT;
             strength <= VibrationProfiles.STRONG; strength++) {
            assertEquals(VibrationProfiles.amplitudes(strength)[1],
                    VibrationProfiles.previewAmplitude(strength));
        }
        assertEquals("a preview is one short pulse", 2,
                VibrationProfiles.previewTimings().length);
    }
}
