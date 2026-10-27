package com.course.tongxiao.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Step 1.3 G2: AlarmRepository behavior, including the §3.3 cascade contract.
 * LiveData is observed on the main looper with a latch, so no extra test rule
 * dependency is needed.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmRepositoryTest {

    private TongxiaoDb db;
    private AlarmRepository repo;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class)
                .allowMainThreadQueries() // test-only
                .build();
        repo = new AlarmRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static Alarm alarm(String label, int hour) {
        Alarm a = new Alarm();
        a.label = label;
        a.hour = hour;
        a.minute = 0;
        return a;
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitFirstValue(LiveData<T> live) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] box = new Object[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                live.observeForever(value -> {
                    if (value != null) {
                        box[0] = value;
                        latch.countDown();
                    }
                }));
        assertTrue("LiveData did not emit within 5s", latch.await(5, TimeUnit.SECONDS));
        return (T) box[0];
    }

    @Test
    public void observeAll_emitsInsertedAlarm() throws Exception {
        repo.insert(alarm("早八", 7));

        List<Alarm> alarms = awaitFirstValue(repo.observeAll());

        assertNotNull(alarms);
        assertEquals(1, alarms.size());
        assertEquals("早八", alarms.get(0).label);
    }

    @Test
    public void deleteWithEvents_removesAlarmAndItsHistory() {
        long rowId = repo.insert(alarm("跑步", 6));
        Alarm stored = db.alarmDao().getById((int) rowId);
        assertNotNull(stored);

        AlarmEvent e1 = new AlarmEvent();
        e1.alarmId = (int) rowId;
        e1.fireAt = 1_000;
        db.alarmEventDao().insert(e1);
        AlarmEvent e2 = new AlarmEvent();
        e2.alarmId = (int) rowId;
        e2.fireAt = 2_000;
        db.alarmEventDao().insert(e2);

        // A second alarm's history must survive the cascade.
        long otherRowId = repo.insert(alarm("早八", 7));
        AlarmEvent foreign = new AlarmEvent();
        foreign.alarmId = (int) otherRowId;
        foreign.fireAt = 3_000;
        db.alarmEventDao().insert(foreign);

        assertEquals(1, repo.deleteWithEvents(stored));

        assertEquals("only the other alarm remains", 1, db.alarmDao().getAll().size());
        assertEquals("its events removed", 1, db.alarmEventDao().getSince(0).size());
        assertEquals("foreign event survived", (int) otherRowId,
                db.alarmEventDao().getSince(0).get(0).alarmId);
    }

    @Test
    public void setEnabled_touchesOnlyEnabled() {
        long rowId = repo.insert(alarm("背单词", 23));
        Alarm stored = db.alarmDao().getById((int) rowId);
        assertNotNull(stored);
        String uidBefore = stored.uid;

        assertEquals(1, repo.setEnabled((int) rowId, false));

        Alarm after = db.alarmDao().getById((int) rowId);
        assertNotNull(after);
        assertFalse(after.enabled);
        assertEquals("uid untouched", uidBefore, after.uid);
        assertEquals("label untouched", "背单词", after.label);
    }

    @Test
    public void getEnabled_returnsOnlyEnabledForScheduler() {
        Alarm on = alarm("开", 6);
        Alarm off = alarm("关", 7);
        off.enabled = false;
        repo.insert(on);
        repo.insert(off);

        List<Alarm> enabled = repo.getEnabled();
        assertEquals(1, enabled.size());
        assertEquals("开", enabled.get(0).label);
    }
}
