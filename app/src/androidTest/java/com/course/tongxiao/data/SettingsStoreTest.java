package com.course.tongxiao.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Step 1.3 G2: SettingsStore defaults and round-trips, on an isolated prefs file. */
@RunWith(AndroidJUnit4.class)
public class SettingsStoreTest {

    /** Isolated file name: never touch the real tongxiao_settings of the app under test. */
    private static final String TEST_FILE = "tongxiao_settings_test";

    private SharedPreferences prefs;
    private SettingsStore store;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs = ctx.getSharedPreferences(TEST_FILE, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new SettingsStore(prefs);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
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
        assertFalse("switch persists off", store.isGroupMasterSwitchOn());

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

    @Test
    public void masterSwitch_isWrittenSynchronously() {
        // Privacy switch uses commit(), so the value is readable immediately and
        // survives an immediate process kill (no apply() fail-open window).
        store.setGroupMasterSwitchOn(false);
        assertEquals(false, prefs.getBoolean("group_master_on", true));
    }
}
