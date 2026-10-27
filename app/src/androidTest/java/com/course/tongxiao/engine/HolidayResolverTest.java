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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Step 2.1 G2: HolidayResolver against the bundled 2026 State Council notice
 * (国办发明电〔2025〕7号). Every statutory block and every makeup workday from the
 * notice has at least one assertion here.
 *
 * <p>Counts are asserted as lower bounds so adding 2027 data does not rot this
 * test; year-exact facts are asserted per date instead.
 */
@RunWith(AndroidJUnit4.class)
public class HolidayResolverTest {

    private HolidayCalendar calendar;
    private HolidayResolver resolver;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        calendar = HolidayCalendar.loadFromAssets(ctx);
        resolver = new HolidayResolver(calendar);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Alarm workdaySmart(boolean skipHolidays) {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.WORKDAY_SMART;
        a.dayMask = 0;
        a.skipHolidays = skipHolidays;
        return a;
    }

    // ---- bundled data integrity ----

    @Test
    public void asset_parsesNoticeTotals() {
        assertTrue("at least the 33 statutory days of 2026", calendar.holidayCount() >= 33);
        assertTrue("at least the 6 makeup workdays of 2026", calendar.makeupCount() >= 6);
        assertTrue("2026 data present", calendar.hasDataFor(2026));
        assertFalse("bundled files must parse", calendar.isDegraded());
    }

    @Test
    public void asset_holidayBlockBoundaries() {
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
            assertTrue(s + " must still ring",
                    resolver.shouldFire(workdaySmart(true), date));
        }
    }

    // ---- statutory holidays skip (WORKDAY_SMART) ----

    @Test
    public void statutoryHolidays_skipSmartWorkdayAlarm_regardlessOfSkipFlag() {
        String[] holidays = {
                "2026-01-01", "2026-02-17", "2026-04-06",
                "2026-05-05", "2026-06-20", "2026-09-26", "2026-10-07",
        };
        for (String s : holidays) {
            assertFalse(s + " must not ring (skipHolidays=true)",
                    resolver.shouldFire(workdaySmart(true), d(s)));
            // WORKDAY_SMART is always holiday-aware: the flag does not apply.
            assertFalse(s + " must not ring (skipHolidays=false)",
                    resolver.shouldFire(workdaySmart(false), d(s)));
        }
    }

    // ---- plain weekdays ----

    @Test
    public void plainWeekdays_ringAndPlainWeekendsDoNot() {
        assertTrue("普通周三", resolver.shouldFire(workdaySmart(true), d("2026-10-14")));
        assertFalse("普通周六", resolver.shouldFire(workdaySmart(true), d("2026-10-17")));
        assertFalse("普通周日", resolver.shouldFire(workdaySmart(true), d("2026-10-18")));
    }

    @Test
    public void workdaySmart_ignoresDayMask() {
        Alarm a = workdaySmart(true);
        a.dayMask = 0; // would exclude every weekday if it were consulted
        assertTrue(resolver.shouldFire(a, d("2026-10-14")));
    }

    // ---- CUSTOM: skipHolidays is the only mode where the flag matters ----

    @Test
    public void customMode_skipHolidaysFlagControlsHolidayFiring() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.CUSTOM;
        a.dayMask = 1 << 5; // Saturday
        LocalDate saturdayHoliday = d("2026-10-03");

        a.skipHolidays = false;
        assertTrue("skipHolidays=false keeps the alarm",
                resolver.shouldFire(a, saturdayHoliday));

        a.skipHolidays = true;
        assertFalse("skipHolidays=true suppresses it",
                resolver.shouldFire(a, saturdayHoliday));
    }

    @Test
    public void dailyMode_alwaysRings_evenOnHolidays() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.DAILY;
        a.skipHolidays = true; // irrelevant for DAILY
        assertTrue("DAILY means every day, holidays included",
                resolver.shouldFire(a, d("2026-10-01")));
        assertTrue(resolver.shouldFire(a, d("2026-02-17")));
    }

    @Test
    public void onceMode_leavesDateSelectionToScheduler() {
        Alarm a = new Alarm();
        a.repeatMode = Alarm.RepeatMode.ONCE;
        assertTrue("ONCE never consults the holiday table here",
                resolver.shouldFire(a, d("2026-10-01")));
    }

    // ---- degraded mode for years without a notice ----

    @Test
    public void unknownYear_degradesToPlainWeekdayLogic() {
        LocalDate monday2027 = d("2027-02-08");
        assertFalse("no 2027 notice bundled yet", resolver.hasDataFor(monday2027));
        assertTrue("a 2027 Monday still rings",
                resolver.shouldFire(workdaySmart(true), monday2027));
        assertFalse("a 2027 Saturday still does not",
                resolver.shouldFire(workdaySmart(true), d("2027-02-13")));
    }

    @Test
    public void malformedDocument_degradesWithoutHalfTable() throws Exception {
        HolidayCalendar broken = HolidayCalendar.of(null, null, null);
        boolean threw = false;
        try {
            broken.mergeJson("{ not json ");
        } catch (java.io.IOException expected) {
            threw = true;
        }
        assertTrue("malformed JSON surfaces as IOException", threw);
        assertEquals("no partial rows committed", 0, broken.holidayCount());
        assertFalse("failed year not marked as loaded", broken.hasDataFor(2026));
    }

    // ---- makeup day wins over holiday on a synthetic collision ----

    @Test
    public void synthetic_dateThatIsBothHolidayAndMakeupDay_rings() {
        LocalDate collision = LocalDate.parse("2026-10-10");
        Set<LocalDate> holidays = new HashSet<>(Collections.singletonList(collision));
        Set<LocalDate> makeup = new HashSet<>(Collections.singletonList(collision));
        Set<Integer> years = new HashSet<>(Collections.singletonList(2026));
        HolidayResolver synthetic = new HolidayResolver(HolidayCalendar.of(holidays, makeup, years));

        assertTrue("makeup workday wins over holiday",
                synthetic.shouldFire(workdaySmart(true), collision));
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
