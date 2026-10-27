package com.course.tongxiao.engine.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.dao.WeatherCacheDao;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.WeatherCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Step 6.2 G2: Open-Meteo payload parsing, the 24h cache rule and the advance
 * clamp (design.md §5.3).
 *
 * <p><b>No test in this class touches the network.</b> The transport is the
 * injectable {@link HttpFetcher}; every case feeds canned JSON, an IOException,
 * or a malformed body. The cache is a fake {@link WeatherCacheDao} that mirrors
 * the real one's contract (PK = city, so one row per city, and a date mismatch
 * returns null). The clock is pinned, so "24h old" is exact rather than flaky.
 */
@RunWith(AndroidJUnit4.class)
public class WeatherClientTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-10-28 22:00 in Shanghai — the nightly pull time, so "tomorrow" is 10-29. */
    private static final Instant NOW = Instant.parse("2026-10-28T14:00:00Z");
    private static final String TODAY = "2026-10-28";
    private static final String TOMORROW = "2026-10-29";
    private static final String CITY = "上海";

    private FakeDao dao;
    private FakeFetcher fetcher;
    private WeatherClient client;

    @Before
    public void setUp() {
        dao = new FakeDao();
        fetcher = new FakeFetcher();
        client = new WeatherClient(dao, fetcher, Clock.fixed(NOW, ZONE));
    }

    // ---- fakes ----

    /** Mirrors the real DAO: one row per city, and a date mismatch misses. */
    private static final class FakeDao implements WeatherCacheDao {

        final Map<String, WeatherCache> byCity = new HashMap<>();
        RuntimeException failure;

        @Override
        public void upsert(WeatherCache row) {
            if (failure != null) {
                throw failure;
            }
            byCity.put(row.city, row);
        }

        @Nullable
        @Override
        public WeatherCache get(String city, String date) {
            if (failure != null) {
                throw failure;
            }
            WeatherCache row = byCity.get(city);
            return row != null && row.date.equals(date) ? row : null;
        }

        @Override
        public void clearAll() {
            byCity.clear();
        }
    }

    private static final class FakeFetcher implements HttpFetcher {

        String body = "";
        IOException failure;
        String lastUrl;
        int calls;

        @NonNull
        @Override
        public String get(@NonNull String url) throws IOException {
            calls++;
            lastUrl = url;
            if (failure != null) {
                throw failure;
            }
            return body;
        }
    }

    private static String forecast(String firstDate, int firstCode, double firstTemp,
                                   String secondDate, int secondCode, double secondTemp) {
        return "{\"latitude\":31.2304,\"longitude\":121.4737,\"timezone\":\"Asia/Shanghai\","
                + "\"daily\":{"
                + "\"time\":[\"" + firstDate + "\",\"" + secondDate + "\"],"
                + "\"weather_code\":[" + firstCode + "," + secondCode + "],"
                + "\"temperature_2m_min\":[" + firstTemp + "," + secondTemp + "]}}";
    }

    private void seedCache(String city, String date, String condition, int minTemp, long fetchedAt) {
        WeatherCache row = new WeatherCache();
        row.city = city;
        row.date = date;
        row.condition = condition;
        row.minTemp = minTemp;
        row.fetchedAt = fetchedAt;
        dao.upsert(row);
    }

    private long millisAgo(Duration duration) {
        return NOW.minus(duration).toEpochMilli();
    }

    // ---- refresh: URL, target day, mapping ----

    @Test
    public void refresh_requestsTheCityCoordinatesAndCachesTomorrow() {
        fetcher.body = forecast(TODAY, 0, 12, TOMORROW, 61, 6.4);

        assertTrue("a parsable payload must be cached", client.refresh(CITY));

        assertEquals("one call, one row per city", 1, fetcher.calls);
        assertTrue("looks up the built-in coordinates of 上海: " + fetcher.lastUrl,
                fetcher.lastUrl.contains("latitude=31.2304")
                        && fetcher.lastUrl.contains("longitude=121.4737"));
        assertTrue("asks for the fields the cache needs",
                fetcher.lastUrl.contains("daily=weather_code,temperature_2m_min"));
        assertTrue("two forecast days so tomorrow is covered",
                fetcher.lastUrl.contains("forecast_days=2"));

        assertEquals(1, dao.byCity.size());
        WeatherCache row = dao.byCity.get(CITY);
        assertNotNull(row);
        assertEquals("the row is for tomorrow, not for the fetch day", TOMORROW, row.date);
        assertEquals(WeatherClient.CONDITION_RAIN, row.condition);
        assertEquals("6.4 rounds to 6", 6, row.minTemp);
        assertEquals("stamped with the injected clock", NOW.toEpochMilli(), row.fetchedAt);
    }

    @Test
    public void refresh_mapsWmoCodes() {
        int[] codes = {0, 2, 3, 45, 61, 82, 95, 71, 86, 66};
        String[] expected = {
                WeatherClient.CONDITION_CLEAR,
                WeatherClient.CONDITION_CLOUDY,
                WeatherClient.CONDITION_CLOUDY,
                WeatherClient.CONDITION_CLOUDY, // fog
                WeatherClient.CONDITION_RAIN,
                WeatherClient.CONDITION_RAIN,   // showers
                WeatherClient.CONDITION_RAIN,   // thunderstorm
                WeatherClient.CONDITION_SNOW,
                WeatherClient.CONDITION_SNOW,   // snow showers
                WeatherClient.CONDITION_RAIN    // freezing rain
        };
        for (int i = 0; i < codes.length; i++) {
            FakeDao freshDao = new FakeDao();
            FakeFetcher freshFetcher = new FakeFetcher();
            freshFetcher.body = forecast(TODAY, 0, 5, TOMORROW, codes[i], 5);
            WeatherClient fresh = new WeatherClient(freshDao, freshFetcher, Clock.fixed(NOW, ZONE));

            assertTrue("code " + codes[i], fresh.refresh(CITY));
            assertEquals("WMO " + codes[i], expected[i], freshDao.byCity.get(CITY).condition);
        }
    }

    @Test
    public void refresh_unknownCity_takesNoNetworkAndCachesNothing() {
        fetcher.body = forecast(TODAY, 0, 12, TOMORROW, 61, 6);

        // 广州 has no built-in coordinates: silently no advance, and definitely
        // no request with another city's latitude pretending to be 广州.
        assertFalse(client.refresh("广州"));
        assertEquals("must not call the network", 0, fetcher.calls);
        assertTrue(dao.byCity.isEmpty());
    }

    @Test
    public void refresh_blankCity_isOff() {
        assertFalse(client.refresh(""));
        assertFalse(client.refresh("   "));
        assertFalse(client.refresh(null));
        assertEquals("empty city = weather feature off", 0, fetcher.calls);
    }

    // ---- refresh: silent degradation ----

    @Test
    public void refresh_networkFailure_isSilentAndChangesNothing() {
        fetcher.failure = new IOException("offline");
        assertFalse(client.refresh(CITY));
        assertTrue(dao.byCity.isEmpty());
    }

    @Test
    public void refresh_badJson_returnsFalseInsteadOfThrowing() {
        String[] payloads = {
                "{ not json at all",
                "[]",
                "{}",
                "{\"daily\":{}}",
                "{\"daily\":{\"time\":[\"" + TOMORROW + "\"],\"weather_code\":[61]}}" // no temp array
        };
        for (String payload : payloads) {
            fetcher.body = payload;
            assertFalse("payload must degrade: " + payload, client.refresh(CITY));
            assertTrue("nothing cached for: " + payload, dao.byCity.isEmpty());
        }
    }

    @Test
    public void refresh_payloadWithoutTomorrow_cachesNothing() {
        fetcher.body = "{\"daily\":{\"time\":[\"" + TODAY + "\"],"
                + "\"weather_code\":[0],\"temperature_2m_min\":[9]}}";
        assertFalse("only tomorrow's row is usable", client.refresh(CITY));
        assertTrue(dao.byCity.isEmpty());
    }

    @Test
    public void refresh_nullTemperature_cachesNothing() {
        fetcher.body = "{\"daily\":{\"time\":[\"" + TOMORROW + "\"],"
                + "\"weather_code\":[0],\"temperature_2m_min\":[null]}}";
        assertFalse("a null temperature must not become 0°C (that would trip the freezing rule)",
                client.refresh(CITY));
        assertTrue(dao.byCity.isEmpty());
    }

    // ---- advanceMinutes: adverse weather ----

    @Test
    public void advanceMinutes_rainIsTenClampedByTheAlarmCap() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());

        assertEquals("cap 5 → the floor", 5, client.advanceMinutes(CITY, TOMORROW, 5));
        assertEquals(10, client.advanceMinutes(CITY, TOMORROW, 15));
        assertEquals(10, client.advanceMinutes(CITY, TOMORROW, 20));
        assertEquals("a cap above the design maximum is clamped to 20",
                10, client.advanceMinutes(CITY, TOMORROW, 600));
    }

    @Test
    public void advanceMinutes_snowIsTen() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_SNOW, 2, NOW.toEpochMilli());
        assertEquals(10, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void advanceMinutes_freezingIsFifteenEvenWhenClear() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_CLEAR, 0, NOW.toEpochMilli());

        assertEquals("minTemp <= 0 alone is adverse", 15, client.advanceMinutes(CITY, TOMORROW, 20));
        assertEquals("still capped by the alarm", 10, client.advanceMinutes(CITY, TOMORROW, 10));
    }

    @Test
    public void advanceMinutes_clearOrCloudy_doesNotAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_CLEAR, 12, NOW.toEpochMilli());
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 20));

        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_CLOUDY, 9, NOW.toEpochMilli());
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    // ---- advanceMinutes: the 24h cache rule ----

    @Test
    public void advanceMinutes_staleCache_doesNotAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, millisAgo(Duration.ofHours(25)));
        assertEquals("stale data must never move the alarm (design.md §5.3)",
                0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void advanceMinutes_cacheJustInside24h_stillAdvances() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8,
                millisAgo(Duration.ofHours(24).minusMinutes(1)));
        assertEquals(10, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void advanceMinutes_futureFetchStamp_isDistrusted() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8,
                NOW.plus(Duration.ofHours(1)).toEpochMilli());
        assertEquals("clock moved backwards: the age is meaningless",
                0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void advanceMinutes_missingOrZeroFetchStamp_doesNotAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, 0);
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    // ---- advanceMinutes: bad inputs ----

    @Test
    public void advanceMinutes_wrongDateOrCity_doesNotAdvance() {
        seedCache(CITY, TODAY, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());
        assertEquals("cache holds another day's row", 0, client.advanceMinutes(CITY, TOMORROW, 20));
        assertEquals("unknown city", 0, client.advanceMinutes("北京", TODAY, 20));
    }

    @Test
    public void advanceMinutes_blankCityOrDate_doesNotAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());
        assertEquals(0, client.advanceMinutes("", TOMORROW, 20));
        assertEquals(0, client.advanceMinutes(null, TOMORROW, 20));
        assertEquals(0, client.advanceMinutes(CITY, null, 20));
        assertEquals(0, client.advanceMinutes(CITY, "  ", 20));
    }

    @Test
    public void advanceMinutes_cityFromSettingsIsTrimmed() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());
        assertEquals("SettingsStore trims on save, but be tolerant anyway",
                10, client.advanceMinutes("  " + CITY + "  ", TOMORROW, 20));
    }

    @Test
    public void advanceMinutes_capBelowTheMinimum_doesNotAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 4));
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 0));
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, -10));
    }

    @Test
    public void advanceMinutes_daoFailure_returnsZeroInsteadOfThrowing() {
        dao.failure = new RuntimeException("db closed");
        assertEquals("a DB failure must never reach the ring path",
                0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    // ---- end to end: refresh → cache → advance ----

    @Test
    public void refreshThenAdvance_endToEndForAFreezingSnowDay() {
        fetcher.body = forecast(TODAY, 3, 9, TOMORROW, 71, -3.4);
        assertTrue(client.refresh(CITY));

        // -3.4 rounds to -3 → freezing → 15 minutes, capped by the alarm setting.
        assertEquals(15, client.advanceMinutes(CITY, TOMORROW, 20));
        assertEquals(12, client.advanceMinutes(CITY, TOMORROW, 12));
    }

    @Test
    public void refreshThenAdvance_endToEndForAMildCloudyDay() {
        fetcher.body = forecast(TODAY, 0, 14, TOMORROW, 3, 11);
        assertTrue(client.refresh(CITY));
        assertEquals("cloudy, warm: normal ring time", 0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void badPayload_thenAdvance_staysAtZero() {
        // The acceptance wording of Step 6.2: a broken payload must degrade to
        // "no advance", not to an exception and not to a guessed time.
        fetcher.body = "{ oops";
        assertFalse(client.refresh(CITY));
        assertEquals(0, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    @Test
    public void failedRefresh_keepsThePreviousGoodRow() {
        // A nightly pull that fails must not wipe the forecast already cached.
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8,
                NOW.minus(Duration.ofHours(1)).toEpochMilli());
        fetcher.failure = new IOException("timeout");

        assertFalse(client.refresh(CITY));
        assertEquals("the older (still fresh) row remains usable",
                10, client.advanceMinutes(CITY, TOMORROW, 20));
    }

    // ---- the scheduler seam ----

    private static long triggerAt(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 29, hour, minute)
                .atZone(ZONE).toInstant().toEpochMilli();
    }

    private static Alarm advanceAlarm(boolean enabled, int maxMinutes) {
        Alarm alarm = new Alarm();
        alarm.hour = 6;
        alarm.minute = 40;
        alarm.advanceEnabled = enabled;
        alarm.advanceMaxMin = maxMinutes;
        return alarm;
    }

    @Test
    public void advancedTriggerAt_movesTheTriggerEarlierByTheAdvance() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());

        long normal = triggerAt(6, 40);
        assertEquals("rain → 10 minutes earlier",
                triggerAt(6, 30), client.advancedTriggerAt(advanceAlarm(true, 20), normal, CITY));
        assertEquals("the cap still applies",
                triggerAt(6, 35), client.advancedTriggerAt(advanceAlarm(true, 5), normal, CITY));
    }

    @Test
    public void advancedTriggerAt_usesTheForecastOfTheDayItRingsOn() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_SNOW, 2, NOW.toEpochMilli());
        long normal = triggerAt(6, 40);
        // The row for TOMORROW drives a TOMORROW morning trigger …
        assertEquals(triggerAt(6, 30),
                client.advancedTriggerAt(advanceAlarm(true, 20), normal, CITY));
        // … and a different day without a row is not touched.
        long anotherDay = java.time.LocalDateTime.of(2026, 10, 30, 6, 40)
                .atZone(ZONE).toInstant().toEpochMilli();
        assertEquals("no forecast row for that date", anotherDay,
                client.advancedTriggerAt(advanceAlarm(true, 20), anotherDay, CITY));
    }

    @Test
    public void advancedTriggerAt_keepsTheNormalTimeWhenAnythingIsOff() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());
        long normal = triggerAt(6, 40);

        assertEquals("per-alarm switch off", normal,
                client.advancedTriggerAt(advanceAlarm(false, 20), normal, CITY));
        assertEquals("blank city = weather off", normal,
                client.advancedTriggerAt(advanceAlarm(true, 20), normal, ""));

        dao.clearAll();
        assertEquals("no cache row at all", normal,
                client.advancedTriggerAt(advanceAlarm(true, 20), normal, CITY));
    }

    @Test
    public void advancedTriggerAt_neverRegistersAPastTrigger() {
        seedCache(CITY, TOMORROW, WeatherClient.CONDITION_RAIN, 8, NOW.toEpochMilli());

        // 2026-10-28 06:40 Shanghai is already in the past relative to NOW (22:00),
        // so a 10-minute advance must NOT be applied — the shifted moment would
        // fire instantly through setAlarmClock.
        long yesterdayMorning = triggerAt(6, 40) - Duration.ofDays(1).toMillis();
        assertEquals("refuses to schedule in the past",
                yesterdayMorning,
                client.advancedTriggerAt(advanceAlarm(true, 20), yesterdayMorning, CITY));

        long justBeforeNow = NOW.minusMillis(1000).toEpochMilli();
        assertEquals(justBeforeNow,
                client.advancedTriggerAt(advanceAlarm(true, 20), justBeforeNow, CITY));
    }
}
