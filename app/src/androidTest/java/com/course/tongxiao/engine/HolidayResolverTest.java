package com.course.tongxiao.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.Alarm;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;

/**
 * Step 2.1 G2: HolidayResolver against the bundled 2026 State Council notice
 * (国办发明电〔2025〕7号). Every statutory holiday block and every makeup workday
 * from the notice has at least one assertion here.
 */
@RunWith(AndroidJUnit4.class)
public class HolidayResolverTest {

    private HolidayCalendar calendar;
    private HolidayResolver resolver;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        calendar = HolidayCalendar.loadFromAssets(ctx);
        resolver = new HolidayResolver(calendar);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Alarm workdaySmart(int dayMask, boolean skipHolidays) {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.WORKDAY_SMART;
        a.dayMask = dayMask;
        a.skipHolidays = skipHolidays;
        return a;
    }

    // ---- bundled data integrity ----

    @Test
    public void asset_parsesNoticeTotals() {
        assertEquals("33 statutory holiday days in 2026", 33, calendar.holidayCount());
        assertEquals("6 makeup workdays in 2026", 6, calendar.makeupCount());
        assertTrue(calendar.hasDataFor(2026));
        assertFalse("2027 notice not published yet", calendar.hasDataFor(2027));
    }

    @Test
    public void asset_holidayBlockBoundaries() {
        // One day inside each block, plus a day right after it.
        String[] inside = {
                "2026-01-01", "2026-02-15", "2026-04-04",
                "2026-05-01", "2026-06-19", "2026-09-25", "2026-10-01"
        };
        String[] after = {
                "2026-01-04", "2026-02-24", "2026-04-07",
                "2026-05-06", "2026-06-22", "2026-09-28", "2026-10-08"
        };
        for (String s : inside) {
            assertTrue(s + " should be a holiday", resolver.isStatutoryHoliday(d(s)));
        }
        for (String s : after) {
            assertFalse(s + " should not be a holiday", resolver.isStatutoryHoliday(d(s)));
        }
    }

    // ---- every makeup workday from the notice ----

    @Test
    public void makeupWorkdays_allSixFireEvenOnWeekends() {
        String[] makeup = {
                "2026-01-04", // Sunday
                "2026-02-14", // Saturday
                "2026-02-28", // Saturday
                "2026-05-09", // Saturday
                "2026-09-20", // Sunday
                "2026-10-10", // Saturday
        };
        for (String s : makeup) {
            LocalDate date = d(s);
            assertTrue(s + " is a makeup workday", resolver.isMakeupWorkday(date));
            assertTrue(s + " must still ring", resolver.shouldFire(workdaySmart(0, true), date));
        }
    }

    // ---- statutory holidays skip ----

    @Test
    public void statutoryHolidays_skipSmartWorkdayAlarm() {
        String[] holidays = {
                "2026-01-01", "2026-02-17", "2026-04-06",
                "2026-05-05", "2026-06-20", "2026-09-26", "2026-10-07",
        };
        for (String s : holidays) {
            assertFalse(s + " must not ring", resolver.shouldFire(workdaySmart(0x7F, true), d(s)));
        }
    }

    // ---- plain weekdays ----

    @Test
    public void plainWeekdays_ringAndPlainWeekendsDoNot() {
        assertTrue("普通周三", resolver.shouldFire(workdaySmart(0, true), d("2026-10-14")));
        assertFalse("普通周六", resolver.shouldFire(workdaySmart(0, true), d("2026-10-17")));
        assertFalse("普通周日", resolver.shouldFire(workdaySmart(0, true), d("2026-10-18")));
    }

    @Test
    public void workdaySmart_ignoresDayMask() {
        // dayMask=0 would exclude every weekday, yet smart mode must ignore it.
        assertTrue(resolver.shouldFire(workdaySmart(0, true), d("2026-10-14")));
    }

    // ---- skipHolidays=false keeps ringing ----

    @Test
    public void customMode_skipHolidaysFalse_firesOnHoliday() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.CUSTOM;
        a.dayMask = 0;
        a.skipHolidays = false;
        LocalDate saturdayHoliday = d("2026-10-03"); // Saturday inside National Day
        // Mark Saturday (bit5) so the weekday rule would allow it.
        a.dayMask = 1 << 5;
        assertTrue("skipHolidays=false keeps the alarm", resolver.shouldFire(a, saturdayHoliday));

        a.skipHolidays = true;
        assertFalse("skipHolidays=true suppresses it", resolver.shouldFire(a, saturdayHoliday));
    }

    @Test
    public void dailyMode_respectsSkipHolidays() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.DAILY;
        a.skipHolidays = true;
        assertFalse(resolver.shouldFire(a, d("2026-10-01")));

        a.skipHolidays = false;
        assertTrue(resolver.shouldFire(a, d("2026-10-01")));
    }

    @Test
    public void onceMode_leavesDateMatchingToScheduler() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.ONCE;
        assertTrue("ONCE fires on its own date; scheduler compares dates",
                resolver.shouldFire(a, d("2026-10-01")));
    }

    // ---- degraded mode for years without a notice ----

    @Test
    public void unknownYear_degradesToPlainWeekdayLogic() {
        LocalDate lunarNewYear2027 = d("2027-02-06"); // Saturday
        assertFalse("no 2027 data", resolver.hasDataFor(lunarNewYear2027));
        assertFalse("weekend stays weekend", resolver.shouldFire(workdaySmart(0, true), lunarNewYear2027));
        assertTrue("a 2027 Wednesday still rings",
                resolver.shouldFire(workdaySmart(0, true), d("2027-02-10")));
    }

    // ---- next-workday deferral ----

    @Test
    public void nextWorkdayAfter_skipsNationalDayBlock() {
        assertEquals("from Sep 30 → Oct 8", d("2026-10-08"),
                resolver.nextWorkdayAfter(d("2026-09-30")));
        assertEquals("from Oct 1 → Oct 8", d("2026-10-08"),
                resolver.nextWorkdayAfter(d("2026-10-01")));
        assertEquals("from Friday Oct 16 → Monday Oct 19", d("2026-10-19"),
                resolver.nextWorkdayAfter(d("2026-10-16")));
    }

    @Test
    public void smartWorkday_definition() {
        assertTrue("makeup Sunday is a workday", resolver.isSmartWorkday(d("2026-01-04")));
        assertFalse("holiday Thursday is not", resolver.isSmartWorkday(d("2026-10-01")));
        assertTrue("plain Wednesday is", resolver.isSmartWorkday(d("2026-10-14")));
        assertFalse("plain Saturday is not", resolver.isSmartWorkday(d("2026-10-17")));
    }

    @Test
    public void dayMaskBitMapping_startsMondayBit0() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.CUSTOM;
        a.skipHolidays = false;
        a.dayMask = 1 << 2; // Wednesday
        assertTrue("Wednesday matches", resolver.shouldFire(a, d("2026-10-14")));
        assertFalse("Thursday does not", resolver.shouldFire(a, d("2026-10-15")));
    }
}
