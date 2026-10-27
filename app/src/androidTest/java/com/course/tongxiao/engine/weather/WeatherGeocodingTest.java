package com.course.tongxiao.engine.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.dao.WeatherCacheDao;
import com.course.tongxiao.data.entity.WeatherCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Geocoding fallback (design.md §5.3): a region picked from the three-level picker
 * is often a district that the built-in four-city table cannot resolve, so the
 * client asks Open-Meteo's geocoder — but it must never accept a same-named place
 * in another province, because that would advance the alarm using the wrong city's
 * weather.
 *
 * <p>Runs entirely against fakes: no network, no clock dependence.
 */
@RunWith(AndroidJUnit4.class)
public class WeatherGeocodingTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-28T14:00:00Z");
    private static final String TOMORROW = LocalDate.of(2026, 10, 29).toString();

    private FakeDao dao;
    private RoutingFetcher fetcher;
    private WeatherClient client;

    @Before
    public void setUp() {
        dao = new FakeDao();
        fetcher = new RoutingFetcher();
        client = new WeatherClient(dao, fetcher, Clock.fixed(NOW, ZONE), true);
    }

    private static String forecastJson() {
        return "{\"daily\":{\"time\":[\"" + TOMORROW + "\",\"" + TOMORROW + "\"],"
                + "\"weather_code\":[61,61],\"temperature_2m_min\":[8.0,8.0]}}";
    }

    private static String geocodeJson(String name, String admin1, String country) {
        return "{\"results\":[{\"name\":\"" + name + "\",\"latitude\":30.2590,"
                + "\"longitude\":120.1300,\"country_code\":\"" + country + "\","
                + "\"admin1\":\"" + admin1 + "\"}]}";
    }

    @Test
    public void districtName_resolvesThroughGeocodingWhenItBelongsToTheRegion() {
        fetcher.geocodeBody = geocodeJson("海曙区", "浙江省", "CN");
        fetcher.forecastBody = forecastJson();

        assertTrue("refresh must succeed via geocoded coordinates",
                client.refresh("浙江省 宁波市 海曙区"));
        assertTrue("a forecast row was written", dao.byCity.containsKey("浙江省 宁波市 海曙区"));
    }

    @Test
    public void sameNamedDistrictInAnotherProvince_isRejected() {
        // The geocoder only returns the first match, and 朝阳区 exists in Beijing too.
        fetcher.geocodeBody = geocodeJson("朝阳区", "北京市", "CN");
        fetcher.forecastBody = forecastJson();

        // Selected region is 吉林省 长春市 朝阳区: the hit belongs to 北京 -> refuse.
        assertTrue("must not use another province's weather",
                !client.refresh("吉林省 长春市 朝阳区"));
        assertTrue("nothing cached", dao.byCity.isEmpty());
    }

    @Test
    public void foreignCountryHit_isRejected() {
        fetcher.geocodeBody = geocodeJson("Springfield", "Illinois", "US");
        fetcher.forecastBody = forecastJson();

        assertTrue(!client.refresh("吉林省 长春市 朝阳区"));
        assertTrue("non-CN hit must be refused", dao.byCity.isEmpty());
    }

    @Test
    public void unresolvablePlace_isCachedAsAMiss_notRetriedEveryRefresh() {
        fetcher.geocodeBody = "{\"results\":[]}";
        fetcher.forecastBody = forecastJson();

        assertTrue(!client.refresh("未知省 未知市 未知区"));
        int callsAfterFirst = fetcher.calls;

        assertTrue(!client.refresh("未知省 未知市 未知区"));
        assertEquals("the negative cache prevents a second network round",
                callsAfterFirst, fetcher.calls);
    }

    @Test
    public void builtInCity_stillResolvesWithoutAnyGeocoding() {
        fetcher.forecastBody = forecastJson();

        assertTrue(client.refresh("浙江省 杭州市 西湖区"));
        assertEquals("built-in table hit: only the forecast call, no geocoding",
                1, fetcher.calls);
    }

    // ---- fakes ----

    private static final class FakeDao implements WeatherCacheDao {
        final Map<String, WeatherCache> byCity = new HashMap<>();

        @Override
        public void upsert(WeatherCache row) {
            byCity.put(row.city, row);
        }

        @Nullable
        @Override
        public WeatherCache get(String city, String date) {
            WeatherCache row = byCity.get(city);
            return row != null && row.date.equals(date) ? row : null;
        }

        @Override
        public void clearAll() {
            byCity.clear();
        }
    }

    /** Returns a geocoding payload or a forecast payload depending on the URL. */
    private static final class RoutingFetcher implements HttpFetcher {
        String geocodeBody = "{\"results\":[]}";
        String forecastBody = "{}";
        int calls;

        @NonNull
        @Override
        public String get(@NonNull String url) throws IOException {
            calls++;
            if (url.contains("geocoding-api")) {
                return geocodeBody;
            }
            if (url.contains("forecast")) {
                return forecastBody;
            }
            throw new IOException("unexpected url: " + url);
        }
    }
}
