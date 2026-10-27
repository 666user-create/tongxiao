package com.course.tongxiao.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.provider.AlarmProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 6.5 G2/G5: the read-only provider answers with the real {@code alarms}
 * rows, the real database column names, and refuses every write.
 *
 * <p>Two entry points are exercised:
 * <ul>
 *   <li>the provider object directly (works independently of the manifest, which
 *       keeps the class testable on its own);</li>
 *   <li>{@code ContentResolver} — the path {@code adb shell content query} uses,
 *       so the manifest declaration is covered too.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class AlarmProviderTest {

    private Context ctx;
    private TongxiaoDb db;
    private AlarmProvider provider;
    private String labelPrefix;
    private final List<Integer> inserted = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);

        provider = new AlarmProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = AlarmProvider.AUTHORITY;
        provider.attachInfo(ctx, info);

        // Unique per run: a stale row from an aborted earlier run must not be
        // counted as one of ours.
        labelPrefix = "provider-test-" + System.nanoTime() + "-";
        for (int i = 0; i < 2; i++) {
            Alarm alarm = new Alarm();
            alarm.label = labelPrefix + i;
            alarm.hour = 6 + i;
            alarm.minute = 30;
            alarm.repeatMode = Alarm.RepeatMode.DAILY;
            alarm.enabled = i == 0;
            inserted.add((int) db.alarmDao().insert(alarm));
        }
    }

    @After
    public void tearDown() {
        for (int id : inserted) {
            db.alarmDao().deleteById(id);
        }
        inserted.clear();
    }

    private String[] ourLabels() {
        return new String[]{labelPrefix + "%"};
    }

    @Test
    public void query_returnsTheRowsWithDatabaseColumnNames() {
        try (Cursor cursor = provider.query(AlarmProvider.CONTENT_URI, null,
                "label LIKE ?", ourLabels(), null)) {
            assertNotNull("provider returns a cursor", cursor);
            assertEquals("both inserted alarms come back", 2, cursor.getCount());
            // Contract: callers see the database column names (design.md §3.1).
            assertTrue("uid column exposed", cursor.getColumnIndex("uid") >= 0);
            assertTrue("hour column exposed", cursor.getColumnIndex("hour") >= 0);
            assertTrue("repeatMode column exposed", cursor.getColumnIndex("repeatMode") >= 0);
            assertTrue("enabled column exposed", cursor.getColumnIndex("enabled") >= 0);
        }
    }

    @Test
    public void query_honoursTheRequestedProjection() {
        try (Cursor cursor = provider.query(AlarmProvider.CONTENT_URI,
                new String[]{"id", "label"}, "label LIKE ?", ourLabels(), null)) {
            assertNotNull(cursor);
            assertEquals("only the requested columns", 2, cursor.getColumnCount());
            assertTrue(cursor.getColumnIndex("label") >= 0);
            assertEquals(2, cursor.getCount());
        }
    }

    @Test
    public void query_rejectsAnUnknownUriInsteadOfReturningEverything() {
        Uri notOurs = Uri.parse("content://" + AlarmProvider.AUTHORITY + "/group_members");
        try {
            provider.query(notOurs, null, null, null, null);
            fail("expected IllegalArgumentException for a non-alarms URI");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported URI"));
        }
    }

    @Test
    public void getType_reportsTheAlarmsDirectoryMimeType() {
        assertEquals(AlarmProvider.MIME_TYPE_ALARMS, provider.getType(AlarmProvider.CONTENT_URI));
        assertTrue(AlarmProvider.MIME_TYPE_ALARMS.startsWith("vnd.android.cursor.dir/"));
    }

    @Test
    public void writes_areRejectedOnEveryPath() {
        try {
            provider.insert(AlarmProvider.CONTENT_URI, new ContentValues());
            fail("insert must be rejected: the provider is read-only");
        } catch (UnsupportedOperationException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        try {
            provider.update(AlarmProvider.CONTENT_URI, new ContentValues(), null, null);
            fail("update must be rejected: the provider is read-only");
        } catch (UnsupportedOperationException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        try {
            provider.delete(AlarmProvider.CONTENT_URI, null, null);
            fail("delete must be rejected: the provider is read-only");
        } catch (UnsupportedOperationException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void contentResolver_seesTheRowsThroughTheDeclaredProvider() {
        // The <provider> declaration lives in AndroidManifest.xml (Lead-owned); if
        // it is missing the resolver query would throw instead of silently passing.
        assertNotNull("AlarmProvider must be declared in AndroidManifest.xml",
                ctx.getPackageManager().resolveContentProvider(AlarmProvider.AUTHORITY, 0));

        try (Cursor cursor = ctx.getContentResolver().query(AlarmProvider.CONTENT_URI,
                new String[]{"id", "label"}, "label LIKE ?", ourLabels(), null)) {
            assertNotNull("resolver query returned null (provider not reachable)", cursor);
            assertEquals("the adb/content-query path sees both rows", 2, cursor.getCount());
        }
    }
}
