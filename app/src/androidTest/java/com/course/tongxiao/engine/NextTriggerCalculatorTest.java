package com.course.tongxiao.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.Alarm;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Step 2.2 G2: next-trigger decision table, including the 2026 holiday blocks,
 * the makeup-workday override and the "strictly after now" boundary.
 */
@RunWith(AndroidJUnit4.class)
public class NextTriggerCalculatorTest {

    private NextTriggerCalculator calc;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        calc = new NextTriggerCalculator(
                new HolidayResolver(HolidayCalendar.loadFromAssets(ctx)));
    }

    private static LocalDateTime at(String isoDateTime) {
        return LocalDateTime.parse(isoDateTime);
    }

    private static Alarm alarm(Alarm.RepeatMode mode, int hour, int minute, int dayMask,
                               boolean skipHolidays) {
        Alarm a = new Alarm();
        a.repeatMode = mode;
        a.hour = hour;
        a.minute = minute;
        a.dayMask = dayMask;
        a.skipHolidays = skipHolidays;
        return a;
    }

    @Test
    public void workdaySmart_sameDayStillInFuture() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals(at("2026-10-14T07:00"), calc.nextTrigger(a, at("2026-10-14T06:00")));
    }

    @Test
    public void workdaySmart_sameDayAlreadyPassed_rollsToNextWorkday() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals("Wed 08:00 → Thu 07:00",
                at("2026-10-15T07:00"), calc.nextTrigger(a, at("2026-10-14T08:00")));
    }

    @Test
    public void workdaySmart_exactNowIsNotAFireTime_strictlyAfter() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals("now == trigger must roll to next workday",
                at("2026-10-15T07:00"), calc.nextTrigger(a, at("2026-10-14T07:00")));
    }

    @Test
    public void once_exactNowIsNotAFireTime() {
        Alarm a = alarm(Alarm.RepeatMode.ONCE, 7, 0, 0, true);
        assertEquals("now == trigger → tomorrow",
                at("2026-10-15T07:00"), calc.nextTrigger(a, at("2026-10-14T07:00")));
    }

    @Test
    public void workdaySmart_fridayEvening_skipsWeekend() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals("Fri 20:00 → Mon 07:00",
                at("2026-10-19T07:00"), calc.nextTrigger(a, at("2026-10-16T20:00")));
    }

    @Test
    public void workdaySmart_skipsWholeNationalDayBlock() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals("Sep 30 20:00 → Oct 8 07:00 (Oct 1-7 holiday)",
                at("2026-10-08T07:00"), calc.nextTrigger(a, at("2026-09-30T20:00")));
    }

    @Test
    public void workdaySmart_ringsOnMakeupSaturday() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        assertEquals("Fri Oct 9 20:00 → Sat Oct 10 07:00 (makeup workday)",
                at("2026-10-10T07:00"), calc.nextTrigger(a, at("2026-10-09T20:00")));
    }

    @Test
    public void once_firesNextOccurrence_evenOnHoliday() {
        Alarm a = alarm(Alarm.RepeatMode.ONCE, 7, 0, 0, true);
        assertEquals("same day in future", at("2026-10-01T07:00"),
                calc.nextTrigger(a, at("2026-09-30T20:00")));
        assertEquals("passed → tomorrow", at("2026-10-01T07:00"),
                calc.nextTrigger(a, at("2026-09-30T08:00")));
    }

    @Test
    public void daily_ringsOnHolidaysToo() {
        Alarm a = alarm(Alarm.RepeatMode.DAILY, 7, 0, 0, true);
        // DAILY means every day: National Day included, no skip.
        assertEquals(at("2026-10-01T07:00"), calc.nextTrigger(a, at("2026-09-30T20:00")));
    }

    @Test
    public void custom_usesDayMask() {
        Alarm wednesdayOnly = alarm(Alarm.RepeatMode.CUSTOM, 7, 0, 1 << 2, true);
        assertEquals("Wed passed → next Wed", at("2026-10-21T07:00"),
                calc.nextTrigger(wednesdayOnly, at("2026-10-14T08:00")));
    }

    @Test
    public void custom_emptyMask_neverFires() {
        Alarm never = alarm(Alarm.RepeatMode.CUSTOM, 7, 0, 0, true);
        assertNull(calc.nextTrigger(never, at("2026-10-14T08:00")));
        assertEquals("millis sentinel is -1", -1L,
                calc.nextTriggerMillis(never, at("2026-10-14T08:00")));
    }

    @Test
    public void millis_isStrictlyAfterNowAndInSystemZone() {
        Alarm a = alarm(Alarm.RepeatMode.WORKDAY_SMART, 7, 0, 0, true);
        LocalDateTime now = at("2026-10-14T06:00");
        long millis = calc.nextTriggerMillis(a, now);

        ZoneId zone = ZoneId.systemDefault();
        assertTrue2("trigger strictly after now",
                millis > now.atZone(zone).toInstant().toEpochMilli());
        LocalDateTime back = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        assertEquals("07:00 local", 7, back.getHour());
        assertEquals("same local day", 14, back.getDayOfMonth());
        assertNotNull(calc.nextTrigger(a, now));
    }

    private static void assertTrue2(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
