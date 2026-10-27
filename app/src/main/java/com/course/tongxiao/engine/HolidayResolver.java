package com.course.tongxiao.engine;

import com.course.tongxiao.data.entity.Alarm;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Pure decision logic: does an alarm fire on a given date? (design.md §5.1)
 *
 * <p>Single source of truth for {@code skipHolidays} (design.md §3.1):
 * <ul>
 *   <li>{@code WORKDAY_SMART} — always holiday-aware: makeup workday rings,
 *       statutory holiday skips, otherwise Mon–Fri. No user switch.</li>
 *   <li>{@code CUSTOM} — honours {@code skipHolidays} if set, then dayMask.</li>
 *   <li>{@code DAILY} — always rings, holidays included ("every day" means
 *       every day).</li>
 *   <li>{@code ONCE} — returns true here; the scheduler picks the next
 *       occurrence of its clock time and never consults holidays.</li>
 * </ul>
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

    /** True when the year's notice is missing or unreadable → UI shows the banner. */
    public boolean hasDataFor(LocalDate date) {
        return calendar.hasDataFor(date.getYear());
    }

    /** Should this alarm ring on {@code date}? See the mode table in the class doc. */
    public boolean shouldFire(Alarm alarm, LocalDate date) {
        switch (alarm.repeatMode) {
            case ONCE:
                return true;
            case DAILY:
                return true;
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
}
