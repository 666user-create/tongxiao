package com.course.tongxiao.engine.weather;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.course.tongxiao.data.dao.WeatherCacheDao;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.WeatherCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weather link: Open-Meteo forecast → {@code weather_cache} → "ring earlier"
 * minutes (design.md §5.3, plan.md Step 6.2).
 *
 * <p>Two responsibilities, both fail-silent:
 * <ul>
 *   <li>{@link #refresh(String)} — nightly pull (design.md: 22:00) of
 *       <b>tomorrow's</b> forecast for the city typed in settings, cached in
 *       {@code weather_cache} with a {@code fetchedAt} stamp. The table keeps one
 *       row per city (PK=city), so each night replaces the previous day's row.</li>
 *   <li>{@link #advanceMinutes(String, String, int)} — read that cache and answer
 *       how many minutes the alarm should ring earlier, clamped into
 *       {@value #MIN_ADVANCE_MINUTES}..{@code maxMinutes}. Zero means "do not
 *       advance".</li>
 *   <li>{@link #advancedTriggerAt(Alarm, long, String)} — the trigger moment a
 *       scheduler should register, i.e. the normal trigger minus the advance.
 *       This is the only place that turns "minutes" into a wall-clock change, so
 *       the "never schedule in the past" guard lives here too.</li>
 * </ul>
 *
 * <p><b>Failure policy (design.md §5.3/§8)</b>: a stale cache (&gt;24h), a future
 * {@code fetchedAt} (clock was changed — the age cannot be trusted), a missing
 * row, an unknown condition, a bad payload or any exception all answer 0. A
 * weather problem must never ring late, never block the ring path and never
 * crash it: silence, not guesses.
 *
 * <p><b>Cities</b> are a small built-in table (北京/上海/杭州/深圳). An unknown
 * city is <b>not</b> mapped to some default coordinate — that would advance the
 * alarm based on another city's weather — it simply yields no advance until the
 * table grows.
 *
 * <p>Threading: both public methods do I/O and are {@link WorkerThread}.
 */
public class WeatherClient {

    private static final String TAG = "WeatherClient";

    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final String GEOCODE_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search";
    private static final int GEOCODE_CACHE_LIMIT = 64;
    private static final java.util.Map<String, double[]> GEOCODE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Paths we already failed to resolve: retrying them only stalls the worker. */
    private static final java.util.Set<String> GEOCODE_MISSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Cached forecast older than this is never used (design.md §5.3). */
    public static final long CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Smallest advance worth reporting; the alarm ignores anything smaller. */
    public static final int MIN_ADVANCE_MINUTES = 5;

    /** Largest advance the design allows ({@code alarms.advanceMaxMin} is 5..20). */
    public static final int MAX_ADVANCE_MINUTES = 20;

    public static final String CONDITION_RAIN = "rain";
    public static final String CONDITION_SNOW = "snow";
    public static final String CONDITION_CLEAR = "clear";
    public static final String CONDITION_CLOUDY = "cloudy";

    /** Default advance for precipitation. */
    private static final int PRECIPITATION_ADVANCE_MIN = 10;
    /** Default advance for freezing temperatures (worse: icy roads, scraping ice). */
    private static final int FREEZING_ADVANCE_MIN = 15;
    private static final int FREEZING_THRESHOLD_C = 0;

    /** Built-in city table: {latitude, longitude}. Unknown city → no network, no advance. */
    private static final Map<String, double[]> CITY_COORDINATES;

    static {
        Map<String, double[]> table = new HashMap<>();
        table.put("北京", new double[]{39.9042, 116.4074});
        table.put("上海", new double[]{31.2304, 121.4737});
        table.put("杭州", new double[]{30.2741, 120.1551});
        table.put("深圳", new double[]{22.5431, 114.0579});
        CITY_COORDINATES = Collections.unmodifiableMap(table);
    }

    private final WeatherCacheDao dao;
    private final HttpFetcher fetcher;
    private final Clock clock;
    /**
     * When true, an unknown place name is resolved to coordinates through the
     * Open-Meteo geocoding API. Production needs it (the region picker can yield
     * thousands of district names); tests keep it off so the existing "unknown
     * city never hits the network" contract stays intact.
     */
    private final boolean geocodeFallback;

    /** Production wiring: real HTTP, device clock, "today" in the device time zone. */
    public WeatherClient(@NonNull WeatherCacheDao dao) {
        this(dao, new HttpUrlFetcher(), Clock.systemDefaultZone(), true);
    }

    /** Test seam: fake transport and/or a pinned clock (geocoding disabled). */
    public WeatherClient(@NonNull WeatherCacheDao dao, @NonNull HttpFetcher fetcher,
                         @NonNull Clock clock) {
        this(dao, fetcher, clock, false);
    }

    /** Full seam, including the geocoding switch. */
    public WeatherClient(@NonNull WeatherCacheDao dao, @NonNull HttpFetcher fetcher,
                         @NonNull Clock clock, boolean geocodeFallback) {
        this.dao = dao;
        this.fetcher = fetcher;
        this.clock = clock;
        this.geocodeFallback = geocodeFallback;
    }

    /**
     * Pulls tomorrow's forecast for {@code city} and stores it in {@code weather_cache}.
     *
     * <p>Only the day after "now" is cached: the advance decision is always made
     * for the next morning's alarm, and the table holds one row per city.
     *
     * @param city city typed in settings; blank = weather link off
     * @return true only when a row was actually written (callers may log/skip)
     */
    @WorkerThread
    public boolean refresh(@Nullable String city) {
        try {
            String key = normalized(city);
            if (key == null) {
                return false; // empty city = feature off (SettingsStore.getCity())
            }
            double[] coordinates = coordinatesFor(key);
            if (coordinates == null) {
                Log.i(TAG, "no coordinates for \"" + key + "\"; weather link stays off");
                return false;
            }
            String body = fetcher.get(buildUrl(coordinates));
            WeatherCache row = parseDaily(body, key, LocalDate.now(clock).plusDays(1));
            if (row == null) {
                Log.w(TAG, "unusable forecast payload; cache left untouched");
                return false;
            }
            row.fetchedAt = clock.millis();
            dao.upsert(row);
            return true;
        } catch (Exception e) {
            // IOException, JSONException, SQLite errors: degrade silently (§8.5).
            Log.w(TAG, "weather refresh failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * How many minutes earlier should the alarm ring on {@code date}?
     *
     * @param city       city from settings; blank → 0
     * @param date       {@code yyyy-MM-dd} of the alarm morning; blank → 0
     * @param maxMinutes per-alarm cap ({@code alarms.advanceMaxMin}); values below
     *                   {@value #MIN_ADVANCE_MINUTES} cannot buy anything → 0
     * @return minutes to advance, clamped into 5..maxMinutes, or 0 for "no advance"
     */
    @WorkerThread
    public int advanceMinutes(@Nullable String city, @Nullable String date, int maxMinutes) {
        try {
            String key = normalized(city);
            if (key == null || date == null || date.trim().isEmpty()) {
                return 0;
            }
            int cap = Math.min(maxMinutes, MAX_ADVANCE_MINUTES);
            if (cap < MIN_ADVANCE_MINUTES) {
                return 0;
            }
            WeatherCache row = dao.get(key, date.trim());
            if (row == null) {
                return 0; // no forecast for that exact day → never advance
            }
            if (!isFresh(row.fetchedAt)) {
                return 0; // stale/implausible timestamp → "宁可不提前" (design.md §5.3)
            }
            int base = baseAdvanceMinutes(row.condition, row.minTemp);
            if (base <= 0) {
                return 0;
            }
            return Math.max(MIN_ADVANCE_MINUTES, Math.min(cap, base));
        } catch (RuntimeException e) {
            // A failing DAO must not reach the ring path (design.md §8.1).
            Log.w(TAG, "weather advance unavailable: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Integration seam for {@code AlarmScheduler.schedule()}: the moment the alarm
     * should actually be registered at, i.e. moved earlier when that morning's
     * forecast is adverse (design.md §5.3).
     *
     * <p>The caller passes the city from settings and the already-computed normal
     * trigger; this method derives the calendar day from the trigger itself (so a
     * 06:40 alarm always asks for the forecast of the morning it rings on) and
     * answers the original value whenever: the per-alarm switch is off, the city
     * is blank, the cache has nothing usable, or shifting would land in the past.
     *
     * <p>That last guard matters: {@code setAlarmClock} with a trigger in the past
     * fires immediately, so "advance an alarm that is only 5 minutes away by 10"
     * would ring the user at once instead of earlier.
     *
     * @return the trigger to register; equal to {@code triggerAtMillis} when the
     *         alarm must not be moved
     */
    @WorkerThread
    public long advancedTriggerAt(@NonNull Alarm alarm, long triggerAtMillis, @Nullable String city) {
        if (!alarm.advanceEnabled || triggerAtMillis <= 0) {
            return triggerAtMillis;
        }
        try {
            LocalDate day = Instant.ofEpochMilli(triggerAtMillis)
                    .atZone(clock.getZone()).toLocalDate();
            int minutes = advanceMinutes(city, day.toString(), alarm.advanceMaxMin);
            if (minutes <= 0) {
                return triggerAtMillis;
            }
            long shifted = triggerAtMillis - minutes * 60_000L;
            if (shifted <= clock.millis()) {
                return triggerAtMillis;
            }
            // Cross-midnight guard: the receiver re-evaluates the weekday/holiday rule
            // against the day the alarm *fires on*. Shifting a 00:05 alarm back to
            // 23:55 would make it fire on the previous day, where a WORKDAY_SMART
            // alarm is "not due" -> it would be skipped and then re-armed, so the
            // advance silently did nothing (and wrote a bogus SKIPPED_HOLIDAY event).
            LocalDate shiftedDay = Instant.ofEpochMilli(shifted)
                    .atZone(clock.getZone()).toLocalDate();
            if (!shiftedDay.equals(day)) {
                return triggerAtMillis;
            }
            return shifted;
        } catch (RuntimeException e) {
            Log.w(TAG, "advance skipped: " + e.getClass().getSimpleName());
            return triggerAtMillis; // 宁可不提前
        }
    }

    private boolean isFresh(long fetchedAt) {
        if (fetchedAt <= 0) {
            return false;
        }
        long age = clock.millis() - fetchedAt;
        // Negative age = the stamp is in the future (clock changed): distrust it
        // rather than treating it as "0h old" forever.
        return age >= 0 && age <= CACHE_TTL_MILLIS;
    }

    /** Adverse-weather policy; 0 means "keep the normal time". */
    private static int baseAdvanceMinutes(@Nullable String condition, int minTemp) {
        if (minTemp <= FREEZING_THRESHOLD_C) {
            return FREEZING_ADVANCE_MIN;
        }
        if (CONDITION_RAIN.equals(condition) || CONDITION_SNOW.equals(condition)) {
            return PRECIPITATION_ADVANCE_MIN;
        }
        return 0;
    }

    // ---- payload parsing ----

    /**
     * Extracts the row for {@code targetDate} from an Open-Meteo daily payload.
     *
     * <p>Returns null instead of guessing when any of the parallel arrays is
     * missing, too short, misaligned, or carries a null temperature: a row with a
     * bogus {@code minTemp} would trigger the freezing rule.
     */
    @Nullable
    private static WeatherCache parseDaily(@Nullable String body, String city, LocalDate targetDate) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject daily = new JSONObject(body).optJSONObject("daily");
            if (daily == null) {
                return null;
            }
            JSONArray dates = daily.optJSONArray("time");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray minTemps = daily.optJSONArray("temperature_2m_min");
            if (dates == null || codes == null || minTemps == null) {
                return null;
            }
            String target = targetDate.toString();
            for (int i = 0; i < dates.length(); i++) {
                if (!target.equals(dates.optString(i))) {
                    continue;
                }
                if (i >= codes.length() || i >= minTemps.length() || minTemps.isNull(i)) {
                    return null; // arrays out of step: cannot trust index i
                }
                double minTemp = minTemps.getDouble(i);
                if (Double.isNaN(minTemp) || Double.isInfinite(minTemp)) {
                    return null;
                }
                WeatherCache row = new WeatherCache();
                row.city = city;
                row.date = target;
                row.condition = conditionOf(codes.optInt(i, -1));
                row.minTemp = (int) Math.round(minTemp);
                return row;
            }
            return null; // payload does not cover the requested day
        } catch (Exception e) {
            return null; // malformed JSON → degrade
        }
    }

    /**
     * WMO weather code → the four conditions the cache/UI use. Unknown codes map
     * to {@link #CONDITION_CLOUDY}: harmless (no advance) but still displayable.
     */
    @NonNull
    private static String conditionOf(int weatherCode) {
        switch (weatherCode) {
            case 0:
                return CONDITION_CLEAR;
            case 51: case 53: case 55: case 56: case 57: // drizzle
            case 61: case 63: case 65: case 66: case 67: // rain
            case 80: case 81: case 82:                   // rain showers
            case 95: case 96: case 99:                   // thunderstorm
                return CONDITION_RAIN;
            case 71: case 73: case 75: case 77:          // snowfall
            case 85: case 86:                            // snow showers
                return CONDITION_SNOW;
            default:
                // 1..3 (mainly clear → overcast), 45/48 (fog) and anything unknown.
                return CONDITION_CLOUDY;
        }
    }

    private static String buildUrl(double[] coordinates) {
        return String.format(Locale.US,
                "%s?latitude=%.4f&longitude=%.4f&daily=weather_code,temperature_2m_min"
                        + "&timezone=auto&forecast_days=2",
                ENDPOINT, coordinates[0], coordinates[1]);
    }

    /**
     * Resolves a stored region path to coordinates.
     *
     * <p>The settings value is a path such as {@code 浙江省 杭州市 西湖区}. Order:
     * <ol>
     *   <li>built-in table, suffix stripped ({@code 杭州市} → {@code 杭州});</li>
     *   <li>geocoding, most specific candidate first, but <b>only when the result
     *       actually belongs to the selected region</b> — Chinese district names
     *       repeat across provinces ({@code 朝阳区} is Beijing and Changchun,
     *       {@code 鼓楼区} is Nanjing/Fuzhou/Xuzhou), and taking the first hit would
     *       mean "advance the alarm based on another city's weather", the exact
     *       failure this class promises to avoid.</li>
     * </ol>
     *
     * @return {@code {latitude, longitude}} or null when nothing credible resolves
     */
    @Nullable
    private double[] coordinatesFor(@NonNull String key) {
        String[] parts = key.trim().split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            double[] builtIn = CITY_COORDINATES.get(stripAdminSuffix(parts[i]));
            if (builtIn != null) {
                return builtIn;
            }
        }
        if (!geocodeFallback) {
            return null;
        }
        // Cache key is the whole path: a leaf-name cache made 长春 朝阳区 inherit
        // 北京 朝阳区's coordinates once the latter had been resolved.
        double[] cached = GEOCODE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (GEOCODE_MISSES.contains(key)) {
            return null; // negative cache: do not retry (and stall) on every refresh
        }
        double[] resolved = geocodePath(key, parts);
        if (resolved == null) {
            if (GEOCODE_MISSES.size() > GEOCODE_CACHE_LIMIT) {
                GEOCODE_MISSES.clear();
            }
            GEOCODE_MISSES.add(key);
            return null;
        }
        if (GEOCODE_CACHE.size() > GEOCODE_CACHE_LIMIT) {
            GEOCODE_CACHE.clear();
        }
        GEOCODE_CACHE.put(key, resolved);
        return resolved;
    }

    /**
     * Tries the most specific usable candidates, at most three requests, and only
     * accepts a hit that belongs to the same region (see {@link #belongsToRegion}).
     */
    @Nullable
    private double[] geocodePath(@NonNull String key, @NonNull String[] parts) {
        List<String> candidates = new ArrayList<>();
        if (parts.length >= 2) {
            candidates.add(parts[parts.length - 1]);                       // district
            candidates.add(parts[Math.min(1, parts.length - 1)]);           // city
        } else if (parts.length == 1) {
            candidates.add(parts[0]);
        }
        // Coarser segments identify the region; the leaf name alone does not, because
        // 朝阳区/鼓楼区/城关区 repeat across provinces.
        List<String> coarseSegments = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            coarseSegments.add(stripAdminSuffix(parts[i]));
        }
        List<String> leafSegments = new ArrayList<>();
        leafSegments.add(stripAdminSuffix(parts[parts.length - 1]));
        for (String candidate : candidates) {
            double[] hit = geocodeOne(key, candidate, coarseSegments, leafSegments);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Nullable
    private double[] geocodeOne(@NonNull String cacheKey, @NonNull String place,
                                @NonNull List<String> coarseSegments,
                                @NonNull List<String> leafSegments) {
        try {
            String body = fetcher.get(buildGeocodeUrl(place));
            JSONObject root = new JSONObject(body);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject hit = results.optJSONObject(i);
                if (hit == null || !belongsToRegion(hit, coarseSegments, leafSegments)) {
                    continue; // same name, different province: not ours
                }
                double latitude = hit.getDouble("latitude");
                double longitude = hit.getDouble("longitude");
                if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                    continue;
                }
                return new double[]{latitude, longitude};
            }
            return null;
        } catch (Exception unresolvable) {
            // Unknown place or offline: no advance rather than a wrong advance.
            Log.i(TAG, "geocoding failed for \"" + place + "\" (" + cacheKey + "): "
                    + unresolvable.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Country must be China (when the API states it) and the hit must match one of
     * the <b>coarser</b> segments (province or city). Matching only the leaf name is
     * not enough: 朝阳区 exists in both 北京 and 长春, so a leaf-only rule would let
     * the wrong city's weather decide whether to advance the alarm.
     */
    private static boolean belongsToRegion(@NonNull JSONObject hit,
                                           @NonNull List<String> coarseSegments,
                                           @NonNull List<String> leafSegments) {
        String country = hit.optString("country_code", "");
        if (!country.isEmpty() && !"CN".equalsIgnoreCase(country)) {
            return false;
        }
        List<String> hitNames = new ArrayList<>();
        for (String field : new String[]{"name", "admin1", "admin2", "admin3"}) {
            String value = hit.optString(field, "");
            if (!value.isEmpty()) {
                hitNames.add(stripAdminSuffix(value));
            }
        }
        if (hitNames.isEmpty()) {
            return false; // nothing to verify against: refuse rather than guess
        }
        for (String segment : coarseSegments) {
            if (!segment.isEmpty() && hitNames.contains(segment)) {
                return true;
            }
        }
        if (!coarseSegments.isEmpty()) {
            return false; // coarser identity known but unmatched -> another province
        }
        // Single-segment path (a bare city/district): the leaf is all we can verify.
        for (String segment : leafSegments) {
            if (!segment.isEmpty() && hitNames.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 浙江省 → 浙江, 杭州市 → 杭州, 西湖区 → 西湖 (only one trailing suffix). */
    @NonNull
    private static String stripAdminSuffix(@NonNull String name) {
        String trimmed = name.trim();
        for (String suffix : new String[]{"特别行政区", "自治区", "省", "市", "区", "县", "旗"}) {
            if (trimmed.length() > suffix.length() && trimmed.endsWith(suffix)) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return trimmed;
    }


    private static String buildGeocodeUrl(@NonNull String place) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(place, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            encoded = place;
        }
        return String.format(Locale.US,
                "%s?name=%s&count=1&language=zh&format=json", GEOCODE_ENDPOINT, encoded);
    }

    @Nullable
    private static String normalized(@Nullable String city) {
        if (city == null) {
            return null;
        }
        String trimmed = city.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- production transport ----

    /** HttpURLConnection implementation: 10s timeouts, explicit User-Agent. */
    private static final class HttpUrlFetcher implements HttpFetcher {

        private static final int TIMEOUT_MILLIS = 10_000;
        private static final String USER_AGENT = "Tongxiao/0.1 (Android; course project)";

        @NonNull
        @Override
        public String get(@NonNull String url) throws IOException {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status);
                }
                StringBuilder body = new StringBuilder();
                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
                return body.toString();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
