package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The ring notification must live on two channels (design.md §5.1).
 *
 * <p>Regression: with only the HIGH-importance channel, the heads-up banner covered the
 * challenge question — the user saw four options and no question (caught while recording
 * the demo video). The quiet twin removes the banner while our own stop UI is visible.
 */
@RunWith(AndroidJUnit4.class)
public class RingChannelsTest {

    @Test
    public void ensureChannel_createsTheLoudAndTheQuietTwin() {
        Context ctx = ApplicationProvider.getApplicationContext();
        RingService.ensureChannel(ctx);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        assertNotNull(nm);

        android.app.NotificationChannel loud = nm.getNotificationChannel(RingService.CHANNEL_ID);
        assertNotNull("loud ring channel exists", loud);
        assertEquals("the loud channel is what an alarm needs",
                NotificationManager.IMPORTANCE_HIGH, loud.getImportance());

        android.app.NotificationChannel quiet = nm.getNotificationChannel(
                RingService.CHANNEL_ID_QUIET);
        assertNotNull("quiet twin exists", quiet);
        assertTrue("the quiet channel must never show a heads-up banner",
                quiet.getImportance() <= NotificationManager.IMPORTANCE_LOW);
        assertEquals("the quiet channel makes no sound (MediaPlayer owns the sound)",
                null, quiet.getSound());
    }

    @Test
    public void setUiVisible_isSafeWhenNothingIsRinging() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Must not throw: the screens call this on every resume, alarm or not.
        RingService.setUiVisible(ctx, true);
        RingService.setUiVisible(ctx, false);
    }
}
