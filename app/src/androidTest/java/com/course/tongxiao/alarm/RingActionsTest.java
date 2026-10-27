package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Step 2.3 G2: the state transitions a ringing session must produce (design.md §2.2). */
@RunWith(AndroidJUnit4.class)
public class RingActionsTest {

    private TongxiaoDb db;
    private AlarmRepository repo;
    private AlarmScheduler scheduler;
    private RingActions actions;
    private Context ctx;
    private final List<Alarm> created = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();
        repo = new AlarmRepository(db);
        scheduler = new AlarmScheduler(ctx, repo,
                new HolidayResolver(HolidayCalendar.loadFromAssets(ctx)));
        actions = new RingActions(db, repo, scheduler);
    }

    @After
    public void tearDown() {
        for (Alarm a : created) {
            scheduler.cancel(a);
        }
        db.close();
    }

    private Alarm insert(Alarm.RepeatMode mode) {
        LocalDateTime t = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        Alarm a = new Alarm();
        a.label = "测试";
        a.repeatMode = mode;
        a.hour = t.getHour();
        a.minute = t.getMinute();
        a.enabled = true;
        a.snoozeMin = 10;
        a.id = (int) repo.insert(a);
        created.add(a);
        return a;
    }

    private boolean hasRegistration(Alarm alarm) {
        Intent intent = new Intent(ctx, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        return pi != null;
    }

    private List<AlarmEvent> events(int alarmId) {
        return db.alarmEventDao().getRecent(alarmId, 0);
    }

    @Test
    public void stoppedTwiceForTheSameRing_recordsOneEventOnly() {
        Alarm a = insert(Alarm.RepeatMode.DAILY);
        scheduler.schedule(a);
        long fireAt = System.currentTimeMillis();

        // Owner taps 停止 and a roommate mutes in the same window: two callers, one ring.
        actions.onStopped(a, fireAt, "user");
        actions.onStopped(a, fireAt + 800, "remote:peer-1");

        List<AlarmEvent> events = events(a.id);
        assertEquals("a single stop must not be counted twice", 1, events.size());
        assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
    }

    @Test
    public void stoppedForADifferentRing_stillRecordsASecondEvent() {
        Alarm a = insert(Alarm.RepeatMode.DAILY);
        scheduler.schedule(a);
        long first = System.currentTimeMillis();
        long nextDay = first + 24L * 60 * 60 * 1000;

        actions.onStopped(a, first, "user");
        actions.onStopped(a, nextDay, "user");

        assertEquals("two different rings are two events", 2, events(a.id).size());
    }
    @Test
    public void stopped_recurringAlarm_recordsEventAndRearms() {
        Alarm a = insert(Alarm.RepeatMode.DAILY);
        scheduler.schedule(a);

        actions.onStopped(a, System.currentTimeMillis(), "user");

        List<AlarmEvent> events = events(a.id);
        assertEquals(1, events.size());
        assertEquals(AlarmEvent.Type.STOPPED, events.get(0).type);
        assertTrue(events.get(0).meta.contains("\"user\""));
        assertTrue("recurring alarm must stay enabled", repo.getAll().get(0).enabled);
        assertTrue("recurring alarm must be re-armed (setAlarmClock is one-shot)",
                hasRegistration(a));
    }

    @Test
    public void stopped_onceAlarm_disablesItselfAndUnregisters() {
        Alarm a = insert(Alarm.RepeatMode.ONCE);
        scheduler.schedule(a);

        actions.onStopped(a, System.currentTimeMillis(), "user");

        Alarm stored = repo.getAll().get(0);
        assertFalse("one-shot alarm turns itself off", stored.enabled);
        assertFalse("no registration left", hasRegistration(a));
        assertEquals(AlarmEvent.Type.STOPPED, events(a.id).get(0).type);
    }

    @Test
    public void snoozed_recordsEventAndArmsAtSnoozeOffset() {
        Alarm a = insert(Alarm.RepeatMode.DAILY);

        long before = System.currentTimeMillis();
        actions.onSnoozed(a, before, 10);

        List<AlarmEvent> events = events(a.id);
        assertEquals(1, events.size());
        assertEquals(AlarmEvent.Type.SNOOZE, events.get(0).type);
        assertTrue(events.get(0).meta.contains("snooze:10"));

        if (scheduler.canScheduleExactAlarms()) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
            long expected = LocalDateTime.now().plusMinutes(10)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            assertTrue("snooze re-armed roughly at now+10min",
                    info != null && Math.abs(info.getTriggerTime() - expected) < 90_000L);
        }
    }

    @Test
    public void snoozed_clampsOutOfRangeValues() {
        Alarm a = insert(Alarm.RepeatMode.DAILY);
        long now = System.currentTimeMillis();

        actions.onSnoozed(a, now - 2_000, 0);
        actions.onSnoozed(a, now - 1_000, 999);

        // Ordered by fireAt: the 0-minute call is the older event.
        List<AlarmEvent> events = events(a.id);
        assertEquals(2, events.size());
        assertTrue("0 minutes is clamped to 1", events.get(0).meta.contains("snooze:1"));
        assertTrue("999 minutes is clamped to 30", events.get(1).meta.contains("snooze:30"));
    }

    @Test
    public void skipped_recordsHolidayEventAndRearmsNextValidDay() {
        Alarm a = insert(Alarm.RepeatMode.WORKDAY_SMART);

        actions.onSkipped(a, System.currentTimeMillis(), "rule: holiday");

        List<AlarmEvent> events = events(a.id);
        assertEquals(1, events.size());
        assertEquals(AlarmEvent.Type.SKIPPED_HOLIDAY, events.get(0).type);
        assertTrue("next valid day re-armed", hasRegistration(a));
    }
}
