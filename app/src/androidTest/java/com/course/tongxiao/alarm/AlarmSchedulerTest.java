package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.NextTriggerCalculator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;

/**
 * Step 2.2 G2/G5: the system really holds the registration we think it does.
 * Assertions go through {@link AlarmManager#getNextAlarmClock()} — the same data
 * `dumpsys alarm` prints — so this is stronger than "no exception thrown".
 */
@RunWith(AndroidJUnit4.class)
public class AlarmSchedulerTest {

    private TongxiaoDb db;
    private AlarmRepository repo;
    private AlarmScheduler scheduler;
    private AlarmManager alarmManager;
    private NextTriggerCalculator calc;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();
        repo = new AlarmRepository(db);

        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(ctx));
        scheduler = new AlarmScheduler(ctx, repo, resolver);
        calc = new NextTriggerCalculator(resolver);
        alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    @After
    public void tearDown() {
        // Leave no real registration behind for the next test or the system UI.
        for (Alarm a : repo.getAll()) {
            scheduler.cancel(a);
        }
        db.close();
    }

    private Alarm insertWorkdayAlarm(String label, int hour, int minute) {
        Alarm a = new Alarm();
        a.label = label;
        a.repeatMode = Alarm.RepeatMode.WORKDAY_SMART;
        a.hour = hour;
        a.minute = minute;
        a.enabled = true;
        long rowId = repo.insert(a);
        a.id = (int) rowId; // Room does not back-fill; the scheduler needs the id
        return a;
    }

    @Test
    public void schedule_registersAnAlarmClock() {
        Alarm a = insertWorkdayAlarm("早八", 7, 0);

        long triggerAt = scheduler.schedule(a);
        assertTrue("schedule returns a future trigger", triggerAt > System.currentTimeMillis());

        if (scheduler.canScheduleExactAlarms()) {
            AlarmManager.AlarmClockInfo info = alarmManager.getNextAlarmClock();
            assertNotNull("system must expose our alarm clock", info);
            assertEquals("registered trigger matches our calculation",
                    calc.nextTriggerMillis(a, LocalDateTime.now()), info.getTriggerTime());
        }
    }

    @Test
    public void reschedule_sameAlarmIsIdempotent() {
        Alarm a = insertWorkdayAlarm("早八", 7, 0);

        long first = scheduler.schedule(a);
        long second = scheduler.schedule(a);
        assertEquals("re-scheduling replaces, never stacks", first, second);

        if (scheduler.canScheduleExactAlarms()) {
            assertNotNull(alarmManager.getNextAlarmClock());
            assertEquals(first, alarmManager.getNextAlarmClock().getTriggerTime());
        }
    }

    @Test
    public void cancel_removesRegistration() {
        Alarm a = insertWorkdayAlarm("跑步", 6, 0);
        scheduler.schedule(a);
        assertNotNull("registered before cancel", alarmManager.getNextAlarmClock());

        scheduler.cancel(a);

        if (scheduler.canScheduleExactAlarms()) {
            AlarmManager.AlarmClockInfo info = alarmManager.getNextAlarmClock();
            assertTrue("no clock registration left for this alarm",
                    info == null || info.getTriggerTime() != calc.nextTriggerMillis(a, LocalDateTime.now()));
        }
    }

    @Test
    public void rescheduleAll_schedulesOnlyEnabled() {
        Alarm enabled = insertWorkdayAlarm("开", 6, 0);
        Alarm disabled = insertWorkdayAlarm("关", 7, 0);
        repo.setEnabled(disabled.id, false);

        int scheduled = scheduler.rescheduleAll();

        assertEquals("only the enabled alarm is (re)scheduled", 1, scheduled);
        assertTrue(repo.getEnabled().size() == 1);
        assertTrue(enabled.id != disabled.id);
    }
}
