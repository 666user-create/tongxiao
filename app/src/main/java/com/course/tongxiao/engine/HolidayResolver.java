package com.course.tongxiao.engine;

import com.course.tongxiao.data.entity.Alarm;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Pure decision logic: does an alarm fire on a given date? (design.md §5.1)
 *
 * <p>Priority is fixed and tested: makeup workday wins (rings even on a
 * Saturday), then statutory holiday (skipped when the alarm asks for it),
 * then the plain weekday rule. A year without holiday data degrades to plain
 * weekday logic.
 */
public class HolidayResolver {

    private final HolidayCalendar calendar;

    public HolidayResolver(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    public boolean isStatutoryHoliday(LocalDate date) {
        return calendar.isStatutoryHoliday(date);
    }

    public boolean isMakeupWorkday(LocalDate date) {
        return calendar.isMakeupWorkday(date);
    }

    /**
     * A "workday" in the smart sense: makeup workdays are workdays, statutory
     * holidays are not, otherwise Mon–Fri. Used by WORKDAY_SMART and by the list
     * screen's "tomorrow is a workday / holiday" subtitle.
     */
    public boolean isSmartWorkday(LocalDate date) {
        if (calendar.isMakeupWorkday(date)) {
            return true;
        }
        if (calendar.isStatutoryHoliday(date)) {
            return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** True when the year's notice is missing → callers show the degraded banner. */
    public boolean hasDataFor(LocalDate date) {
        return calendar.hasDataFor(date.getYear());
    }

    /**
     * Should this alarm ring on {@code date}?
     *
     * <p>ONCE alarms return true here: whether the date matches is the
     * scheduler's job (exact-date comparison), not a weekday rule.
     */
    public boolean shouldFire(Alarm alarm, LocalDate date) {
        switch (alarm.repeatMode) {
            case ONCE:
                return true;
            case DAILY:
                return !alarm.skipHolidays || !calendar.isStatutoryHoliday(date);
            case CUSTOM: {
                if (alarm.skipHolidays && calendar.isStatutoryHoliday(date)) {
                    return false;
                }
                return isDayMaskSet(alarm.dayMask, date.getDayOfWeek());
            }
            case WORKDAY_SMART:
            default:
                return isSmartWorkday(date);
        }
    }

    /** bit0=Mon ... bit6=Sun (Alarm.dayMask contract). */
    static boolean isDayMaskSet(int dayMask, DayOfWeek dow) {
        int bit = dow.getValue() - 1; // Mon=1 -> bit0
        return (dayMask & (1 << bit)) != 0;
    }

    /**
     * Next smart workday strictly after {@code date}. Used when a one-shot alarm
     * lands inside a holiday and the user accepts "move to next workday".
     */
    public LocalDate nextWorkdayAfter(LocalDate date) {
        LocalDate d = date.plusDays(1);
        for (int guard = 0; guard < 30; guard++) {
            if (isSmartWorkday(d)) {
                return d;
            }
            d = d.plusDays(1);
        }
        return d; // pathological data; caller shows it as-is
    }
}
