package com.course.tongxiao.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.data.entity.WeatherCache;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.weather.WeatherClient;
import com.course.tongxiao.engine.NextTriggerCalculator;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 2.2 G2/G5: the system really holds the registration we think it does.
 *
 * <p>Assertions read {@link AlarmManager#getNextAlarmClock()} (same state
 * `dumpsys alarm` prints) and probe our own PendingIntents with
 * {@code FLAG_NO_CREATE}, so "registered" and "gone" are both evidenced.
 * Exact-alarm assertions use {@code Assume} so a revoked-permission device
 * reports a skip instead of a silent pass.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmSchedulerTest {

    private TongxiaoDb db;
    private AlarmRepository repo;
    private AlarmScheduler scheduler;
    private AlarmManager alarmManager;
    private NextTriggerCalculator calc;
    private Context ctx;
    private final List<Alarm> created = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TongxiaoDb.class).build();
        repo = new AlarmRepository(db);

        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(ctx));
        scheduler = new AlarmScheduler(ctx, repo, resolver);
        calc = new NextTriggerCalculator(resolver);
        alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    @After
    public void tearDown() {
        for (Alarm a : created) {
            scheduler.cancel(a);
        }
        db.close();
    }

    /**
     * DAILY alarm two hours from now: far away from any hour boundary so the
     * test cannot race a real trigger or a "now == trigger" edge.
     */
    private Alarm insertDailySoon() {
        LocalDateTime target = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        Alarm a = new Alarm();
        a.label = "测试";
        a.repeatMode = Alarm.RepeatMode.DAILY;
        a.hour = target.getHour();
        a.minute = target.getMinute();
        a.enabled = true;
        long rowId = repo.insert(a);
        a.id = (int) rowId; // Room does not back-fill; the scheduler needs the id
        created.add(a);
        return a;
    }

    @Test
    public void schedule_appliesTheWeatherAdvanceWhenTheAlarmOptedIn() {
        // This is the wiring test for design.md §5.3: the advance must be part of the
        // REGISTRATION, and the edit screen's switch is the only writer of
        // advanceEnabled. Before this test existed nothing ever set that flag, so the
        // feature was dead code in production while the UI still advertised it.
        com.course.tongxiao.data.SettingsStore settings =
                new com.course.tongxiao.data.SettingsStore(ctx);
        String previousCity = settings.getCity();
        try {
            settings.setCity("杭州市");
            LocalDate today = LocalDate.now();
            WeatherCache row = new WeatherCache();
            row.city = "杭州市";
            row.date = today.toString();
            row.condition = WeatherClient.CONDITION_RAIN;
            row.minTemp = 8;
            row.fetchedAt = System.currentTimeMillis();
            TongxiaoDb.get(ctx).weatherCacheDao().upsert(row);

            Alarm plain = insertDailySoon();
            Alarm advanced = insertDailySoon();
            advanced.advanceEnabled = true;
            advanced.advanceMaxMin = 20;

            long plainTrigger = scheduler.schedule(plain);
            long advancedTrigger = scheduler.schedule(advanced);

            assertTrue(plainTrigger > 0 && advancedTrigger > 0);
            assertEquals("rain advances the alarm by 10 minutes",
                    plainTrigger - advancedTrigger, 10L * 60 * 1000);
        } finally {
            settings.setCity(previousCity);
            TongxiaoDb.get(ctx).weatherCacheDao().clearAll();
        }
    }

    @Test
    public void schedule_doesNotAdvanceAlarmsThatDidNotOptIn() {
        com.course.tongxiao.data.SettingsStore settings =
                new com.course.tongxiao.data.SettingsStore(ctx);
        String previousCity = settings.getCity();
        try {
            settings.setCity("杭州市");
            WeatherCache row = new WeatherCache();
            row.city = "杭州市";
            row.date = LocalDate.now().toString();
            row.condition = WeatherClient.CONDITION_SNOW;
            row.minTemp = -5;
            row.fetchedAt = System.currentTimeMillis();
            TongxiaoDb.get(ctx).weatherCacheDao().upsert(row);

            Alarm plain = insertDailySoon();
            long normalTrigger = calc.nextTriggerMillis(plain, LocalDateTime.now());
            assertEquals("no opt-in means no shift",
                    normalTrigger, scheduler.schedule(plain));
        } finally {
            settings.setCity(previousCity);
            TongxiaoDb.get(ctx).weatherCacheDao().clearAll();
        }
    }
    /** Does our PendingIntent for this alarm exist right now? */
    private boolean hasPendingAlarm(Alarm alarm) {
        Intent intent = new Intent(ctx, AlarmReceiver.class)
                .setAction(AlarmIntents.ACTION_FIRE_ALARM)
                .putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmIntents.EXTRA_ALARM_UID, alarm.uid);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, alarm.id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        return pi != null;
    }

    @Test
    public void schedule_registersAnAlarmClock_andCreatesOurPendingIntent() {
        Alarm a = insertDailySoon();

        long triggerAt = scheduler.schedule(a);

        assertTrue("future trigger returned", triggerAt > System.currentTimeMillis());
        assertTrue("our PendingIntent exists", hasPendingAlarm(a));

        Assume.assumeTrue("device grants exact alarms", scheduler.canScheduleExactAlarms());
        AlarmManager.AlarmClockInfo info = alarmManager.getNextAlarmClock();
        assertNotNull("system exposes an alarm clock", info);
        assertEquals("registered trigger matches our calculation",
                calc.nextTriggerMillis(a, LocalDateTime.now()), info.getTriggerTime());
    }

    @Test
    public void reschedule_sameAlarmIsIdempotent_andNewTimeReplacesOld() {
        Alarm a = insertDailySoon();
        long first = scheduler.schedule(a);

        // Move the alarm one hour later and schedule again: the registration must
        // move, not stack a second one.
        a.hour = (a.hour + 1) % 24;
        repo.update(a);
        long second = scheduler.schedule(a);

        assertTrue("new trigger differs from old", second != first);
        assertEquals("matches calculation for the new time",
                calc.nextTriggerMillis(a, LocalDateTime.now()), second);

        Assume.assumeTrue("device grants exact alarms", scheduler.canScheduleExactAlarms());
        assertEquals("system now holds only the new time",
                second, alarmManager.getNextAlarmClock().getTriggerTime());
    }

    @Test
    public void cancel_removesRegistrationAndPendingIntent() {
        Alarm a = insertDailySoon();
        scheduler.schedule(a);
        assertTrue(hasPendingAlarm(a));

        scheduler.cancel(a);

        assertFalse("PendingIntent gone", hasPendingAlarm(a));
        Assume.assumeTrue("device grants exact alarms", scheduler.canScheduleExactAlarms());
        AlarmManager.AlarmClockInfo info = alarmManager.getNextAlarmClock();
        assertTrue("no clock registration left for this alarm",
                info == null || info.getTriggerTime() != calc.nextTriggerMillis(a, LocalDateTime.now()));
    }

    @Test
    public void schedule_disabledAlarm_neverRegisters() {
        Alarm a = insertDailySoon();
        a.enabled = false;

        long triggerAt = scheduler.schedule(a);

        assertEquals("disabled alarms are not scheduled", -1L, triggerAt);
        assertFalse("no registration created", hasPendingAlarm(a));
    }

    @Test
    public void rescheduleAll_dropsStaleRegistrationOfNewlyDisabledAlarm() {
        Alarm enabled = insertDailySoon();
        Alarm disabledLater = insertDailySoon();
        disabledLater.hour = (disabledLater.hour + 1) % 24;
        repo.update(disabledLater);

        // Both registered first...
        assertTrue(scheduler.schedule(enabled) > 0);
        assertTrue(scheduler.schedule(disabledLater) > 0);
        assertTrue(hasPendingAlarm(enabled));
        assertTrue(hasPendingAlarm(disabledLater));

        // ...then one is switched off and a full sweep runs.
        repo.setEnabled(disabledLater.id, false);
        disabledLater.enabled = false;
        int scheduled = scheduler.rescheduleAll();

        assertEquals("only the enabled alarm is scheduled", 1, scheduled);
        assertTrue("enabled registration kept", hasPendingAlarm(enabled));
        assertFalse("stale registration of disabled alarm dropped", hasPendingAlarm(disabledLater));

        assertNotNull("enabled alarm still in repository", repo.getEnabled().isEmpty()
                ? null : enabled);
        assertNull("disabled alarm not in enabled list",
                repo.getEnabled().stream().filter(x -> x.id == disabledLater.id).findFirst().orElse(null));
    }
}
