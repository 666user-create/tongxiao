package com.course.tongxiao.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.work.AlarmRegistrationCheck;
import com.course.tongxiao.work.SelfCheckWorker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Step 6.4 G2/G5: the self-check must repair a registration that really went
 * missing, and must not touch one that is healthy.
 *
 * <p>The counterexample is produced for real: the alarm is scheduled, then
 * {@code AlarmScheduler.cancel()} removes the {@code AlarmManager} registration,
 * and the test asserts the PendingIntent probe sees nothing — only then is the
 * sweep allowed to prove itself by putting the registration back.
 *
 * <p>Uses the app-wide DB (the same one the worker reads in production) so alarm
 * ids stay unique across tests; every row it creates is cancelled and deleted
 * afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class SelfCheckWorkerTest {

    private Context ctx;
    private TongxiaoDb db;
    private AlarmRepository repository;
    private AlarmScheduler scheduler;
    private final List<Alarm> created = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);
        repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(ctx));
        scheduler = new AlarmScheduler(ctx, repository, resolver);
    }

    @After
    public void tearDown() {
        for (Alarm alarm : created) {
            scheduler.cancel(alarm);
            db.alarmDao().deleteById(alarm.id);
        }
        created.clear();
        WorkManager workManager = WorkManager.getInstance(ctx);
        workManager.cancelUniqueWork(SelfCheckWorker.UNIQUE_PERIODIC_WORK);
        workManager.cancelUniqueWork(SelfCheckWorker.UNIQUE_ONE_SHOT_WORK);
    }

    /** DAILY alarm two hours out: far from any boundary, so it cannot fire mid-test. */
    private Alarm insertAlarm(boolean enabled) {
        LocalDateTime target = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        Alarm alarm = new Alarm();
        alarm.label = "自检测试";
        alarm.repeatMode = Alarm.RepeatMode.DAILY;
        alarm.hour = target.getHour();
        alarm.minute = target.getMinute();
        alarm.enabled = enabled;
        alarm.id = (int) db.alarmDao().insert(alarm);
        created.add(alarm);
        return alarm;
    }

    @Test
    public void check_reArmsARegistrationThatWentMissing() {
        Alarm alarm = insertAlarm(true);
        assertTrue("precondition: scheduling registers the alarm", scheduler.schedule(alarm) > 0);
        assertTrue(AlarmRegistrationCheck.isRegistered(ctx, alarm));

        scheduler.cancel(alarm);
        assertFalse("counterexample precondition: the registration is really gone",
                AlarmRegistrationCheck.isRegistered(ctx, alarm));

        AlarmRegistrationCheck.Report report = SelfCheckWorker.checkNow(ctx, repository, scheduler);

        assertTrue("the sweep must put the missing registration back",
                AlarmRegistrationCheck.isRegistered(ctx, alarm));
        assertTrue("the sweep must report the gap: " + report, report.missing() >= 1);
        assertTrue("the sweep must count the repair: " + report, report.repaired >= 1);
    }

    @Test
    public void check_isANoOpOnAnAlreadyHealthyRegistration() {
        Alarm alarm = insertAlarm(true);
        assertTrue(scheduler.schedule(alarm) > 0);

        // First sweep settles anything a previous test left behind...
        SelfCheckWorker.checkNow(ctx, repository, scheduler);
        // ...so the second sweep must find nothing to do: a healthy registration
        // is recognised by the probe instead of being re-armed every 6h.
        AlarmRegistrationCheck.Report second = SelfCheckWorker.checkNow(ctx, repository, scheduler);

        assertEquals("nothing may be missing after a settling sweep: " + second,
                0, second.missing());
        assertEquals("nothing may be repaired on a healthy set: " + second,
                0, second.repaired);
        assertTrue("healthy registration kept", AlarmRegistrationCheck.isRegistered(ctx, alarm));
    }

    @Test
    public void check_neverArmsDisabledAlarms() {
        Alarm disabled = insertAlarm(false);

        SelfCheckWorker.checkNow(ctx, repository, scheduler);

        assertFalse("a disabled alarm must not be armed by the self-check",
                AlarmRegistrationCheck.isRegistered(ctx, disabled));
    }

    @Test
    public void schedule_registersExactlyOnePeriodicChain() throws Exception {
        SelfCheckWorker.schedule(ctx);
        SelfCheckWorker.schedule(ctx); // KEEP: the second call must not stack a chain

        List<WorkInfo> infos = WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWork(SelfCheckWorker.UNIQUE_PERIODIC_WORK)
                .get(10, TimeUnit.SECONDS);

        assertEquals("unique periodic work exists exactly once", 1, infos.size());
        assertFalse("periodic work never reaches a finished state",
                infos.get(0).getState().isFinished());
    }

    @Test
    public void runOnce_enqueuesAOneShotSelfCheck() throws Exception {
        SelfCheckWorker.runOnce(ctx);

        List<WorkInfo> infos = WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWork(SelfCheckWorker.UNIQUE_ONE_SHOT_WORK)
                .get(10, TimeUnit.SECONDS);

        boolean enqueued = false;
        for (WorkInfo info : infos) {
            if (info.getState() != WorkInfo.State.CANCELLED) {
                enqueued = true;
            }
        }
        assertTrue("startup one-shot self-check must be enqueued", enqueued);
    }
}
