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
 * Immutable holiday/makeup-workday table, parsed from the bundled assets
 * (design.md §5.1).
 *
 * <p>Resilience rule ("宁响勿哑"): a missing <em>or malformed</em> file degrades
 * to plain weekday logic and raises {@link #isDegraded()}; it never throws into
 * the alarm path. Parsing is transactional per file — the data is assembled in
 * temporary sets and only merged when the whole document parsed, so a broken
 * file cannot leave half a year behind.
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
    private boolean degraded;

    private HolidayCalendar() {
    }

    /** Seam for synthetic fixtures (e.g. a date that is both holiday and makeup day). */
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

    public static HolidayCalendar loadFromAssets(Context appContext) {
        AssetManager assets = appContext.getAssets();
        HolidayCalendar calendar = new HolidayCalendar();
        for (String name : ASSET_FILES) {
            String json;
            try {
                json = readAsset(assets, name);
            } catch (IOException missing) {
                continue; // year not bundled yet — normal for next year
            }
            try {
                calendar.mergeJson(json);
            } catch (Exception broken) {
                // Keep whatever earlier files contributed; mark degraded so the UI
                // can warn instead of silently trusting a half table.
                calendar.degraded = true;
            }
        }
        return calendar;
    }

    /**
     * Parses one year document and merges it in atomically.
     *
     * @throws IOException when the document is malformed; the calendar is unchanged.
     */
    public void mergeJson(String json) throws IOException {
        Set<LocalDate> holidays = new HashSet<>();
        Set<LocalDate> makeup = new HashSet<>();
        int year;
        try {
            JSONObject root = new JSONObject(json);
            year = root.getInt("year");

            JSONArray holidayArray = root.optJSONArray("holidays");
            if (holidayArray != null) {
                for (int i = 0; i < holidayArray.length(); i++) {
                    JSONObject item = holidayArray.getJSONObject(i);
                    LocalDate from = LocalDate.parse(item.getString("from"));
                    LocalDate to = LocalDate.parse(item.getString("to"));
                    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                        holidays.add(d);
                    }
                }
            }

            JSONArray extra = root.optJSONArray("workdays_extra");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    makeup.add(LocalDate.parse(extra.getJSONObject(i).getString("date")));
                }
            }
        } catch (Exception e) {
            throw new IOException("malformed holiday JSON: " + e.getMessage(), e);
        }
        // Commit only after the whole document parsed.
        statutoryHolidays.addAll(holidays);
        makeupWorkdays.addAll(makeup);
        yearsWithData.add(year);
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

    /** True when any bundled document failed to parse — the UI must warn. */
    public boolean isDegraded() {
        return degraded;
    }

    public int holidayCount() {
        return statutoryHolidays.size();
    }

    public int makeupCount() {
        return makeupWorkdays.size();
    }
}
