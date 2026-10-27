package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** design.md §3.1 deviceId (stable UUID) and §4 group code, persisted separately. */
@RunWith(AndroidJUnit4.class)
public class GroupIdentityTest {

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("test_group_identity", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void deviceId_isGeneratedOnceAndStaysStable() {
        GroupIdentity first = new GroupIdentity(prefs);
        String id = first.deviceId();
        assertTrue("deviceId generated", id != null && !id.isEmpty());

        GroupIdentity second = new GroupIdentity(prefs);
        assertEquals("a new instance must reuse the stored identity", id, second.deviceId());

        GroupIdentity other = new GroupIdentity(
                ApplicationProvider.getApplicationContext()
                        .getSharedPreferences("test_group_identity_b", Context.MODE_PRIVATE));
        other.deviceId();
        assertNotEquals("different devices must not share an id", id, other.deviceId());
    }

    @Test
    public void nick_defaultsToANonEmptyName() {
        GroupIdentity identity = new GroupIdentity(prefs);
        assertNotNull(identity.nick());
        assertFalse(identity.nick().trim().isEmpty());

        identity.setNick("  小明  ");
        assertEquals("小明", identity.nick());
    }

    @Test
    public void groupCode_rejectsAnythingButSixDigits() {
        GroupIdentity identity = new GroupIdentity(prefs);
        assertEquals("", identity.groupCode());

        assertFalse(identity.setGroupCode("12345"));
        assertFalse(identity.setGroupCode("abcdef"));
        assertEquals("a rejected code must not be stored", "", identity.groupCode());

        assertTrue(identity.setGroupCode("654321"));
        assertEquals("654321", identity.groupCode());

        assertTrue("empty code = leaving the group", identity.setGroupCode(""));
        assertEquals("", identity.groupCode());
    }
}
