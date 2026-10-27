package com.course.tongxiao.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Step 1.3 G2: SettingsStore defaults and round-trips. */
@RunWith(AndroidJUnit4.class)
public class SettingsStoreTest {

    private SettingsStore store;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Start from a clean prefs file so defaults are observable.
        ctx.getSharedPreferences("tongxiao_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        store = new SettingsStore(ctx);
    }

    @Test
    public void defaults_areEngineFriendly() {
        assertNull("null = inherit challenge engine defaults (Step 4.1)",
                store.getDefaultChallengeJson());
        assertEquals("empty city means weather off", "", store.getCity());
        assertTrue("group permissions default on", store.isGroupMasterSwitchOn());
    }

    @Test
    public void setters_roundTrip() {
        store.setDefaultChallengeJson("{\"questionCount\":2}");
        assertEquals("{\"questionCount\":2}", store.getDefaultChallengeJson());

        store.setCity("  杭州  ");
        assertEquals("city is trimmed", "杭州", store.getCity());

        store.setGroupMasterSwitchOn(false);
        assertTrue(!store.isGroupMasterSwitchOn());

        store.setDefaultChallengeJson(null);
        assertNull("clearing override falls back to engine defaults",
                store.getDefaultChallengeJson());
    }

    @Test
    public void setCity_nullBecomesEmptyNotCrash() {
        store.setCity("北京");
        store.setCity(null);
        assertEquals("", store.getCity());
    }
}
