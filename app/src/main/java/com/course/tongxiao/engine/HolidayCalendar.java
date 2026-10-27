package com.course.tongxiao.engine;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable holiday/makeup-workday table for one or more years, parsed from the
 * bundled assets JSON (design.md §5.1).
 *
 * <p>Data source of truth: each file carries the State Council notice it came
 * from. A year with no file is "unknown": {@link #hasDataFor(int)} returns false
 * and the app degrades to plain weekday logic with a warning banner — it must
 * never silently assume a day is a workday.
 */
public class HolidayCalendar {

    /** Bundled asset names, tried in order. Missing ones are skipped. */
    private static final String[] ASSET_FILES = {
            "holidays-2026.json",
            "holidays-2027.json",
    };

    private final Set<LocalDate> statutoryHolidays = new HashSet<>();
    private final Set<LocalDate> makeupWorkdays = new HashSet<>();
    private final Set<Integer> yearsWithData = new HashSet<>();

    private HolidayCalendar() {
    }

    /** Test/fixture seam: build directly from sets without touching assets. */
    public static HolidayCalendar of(Set<LocalDate> holidays,
                                     Set<LocalDate> makeupDays,
                                     Set<Integer> years) {
        HolidayCalendar c = new HolidayCalendar();
        if (holidays != null) {
            c.statutoryHolidays.addAll(holidays);
        }
        if (makeupDays != null) {
            c.makeupWorkdays.addAll(makeupDays);
        }
        if (years != null) {
            c.yearsWithData.addAll(years);
        }
        return c;
    }

    public static HolidayCalendar loadFromAssets(Context appContext) throws IOException {
        AssetManager assets = appContext.getAssets();
        HolidayCalendar calendar = new HolidayCalendar();
        for (String name : ASSET_FILES) {
            String json;
            try {
                json = readAsset(assets, name);
            } catch (IOException missing) {
                continue; // year not published yet
            }
            calendar.mergeJson(json);
        }
        return calendar;
    }

    /** Parses one year document and merges it in. Package-visible for tests. */
    public void mergeJson(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            int year = root.getInt("year");
            yearsWithData.add(year);

            JSONArray holidays = root.optJSONArray("holidays");
            if (holidays != null) {
                for (int i = 0; i < holidays.length(); i++) {
                    JSONObject item = holidays.getJSONObject(i);
                    LocalDate from = LocalDate.parse(item.getString("from"));
                    LocalDate to = LocalDate.parse(item.getString("to"));
                    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                        statutoryHolidays.add(d);
                    }
                }
            }

            JSONArray extra = root.optJSONArray("workdays_extra");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    makeupWorkdays.add(LocalDate.parse(
                            extra.getJSONObject(i).getString("date")));
                }
            }
        } catch (Exception e) {
            throw new IOException("malformed holiday JSON: " + e.getMessage(), e);
        }
    }

    private static String readAsset(AssetManager assets, String name) throws IOException {
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public boolean isStatutoryHoliday(LocalDate date) {
        return statutoryHolidays.contains(date);
    }

    public boolean isMakeupWorkday(LocalDate date) {
        return makeupWorkdays.contains(date);
    }

    /** False when no notice has been imported for that year (degraded mode). */
    public boolean hasDataFor(int year) {
        return yearsWithData.contains(year);
    }

    public int holidayCount() {
        return statutoryHolidays.size();
    }

    public int makeupCount() {
        return makeupWorkdays.size();
    }
}
