package com.course.tongxiao.engine.region;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Province → city → district directory parsed from {@code assets/regions.json}
 * (design.md §7 ⑤).
 *
 * <p>The asset is a name-only, whitespace-free JSON document (~54 KB) built from
 * the National Bureau of Statistics 2023 statistical division codes; its
 * {@code src}/{@code fetched}/{@code license} fields record provenance. Codes,
 * pinyin and coordinates were dropped on purpose: the picker only needs names and
 * the alarm APK ships the whole country, so size is a product constraint.
 *
 * <p>Resilience rule (same as {@link com.course.tongxiao.engine.HolidayCalendar}):
 * a missing or malformed asset <b>degrades</b> to an empty catalog and raises
 * {@link #isDegraded()}; it never throws into the settings screen and never
 * crashes the alarm app. Parsing is all-or-nothing — a structurally broken
 * document leaves no half directory behind, because a partially loaded tree would
 * silently hide districts the user needs. Every query tolerates {@code null},
 * unknown and blank input by returning an empty list.
 */
public final class RegionCatalog {

    /** Bundled asset name. */
    private static final String ASSET_NAME = "regions.json";

    /** Schema version this parser understands; anything else degrades. */
    private static final int SCHEMA_VERSION = 1;

    /** Shared instance returned whenever the data is unusable (immutable, safe to share). */
    private static final RegionCatalog DEGRADED =
            new RegionCatalog(Collections.<String, Province>emptyMap(), true, 0, 0);

    /** Province name → its cities; insertion order = official code order. */
    @NonNull
    private final Map<String, Province> provincesByName;
    @NonNull
    private final List<String> provinceNames;
    private final boolean degraded;
    private final int totalDistricts;
    private final int totalCities;

    private RegionCatalog(@NonNull Map<String, Province> provincesByName, boolean degraded,
                          int totalCities, int totalDistricts) {
        this.provincesByName = provincesByName;
        this.provinceNames = Collections.unmodifiableList(new ArrayList<>(provincesByName.keySet()));
        this.degraded = degraded;
        this.totalCities = totalCities;
        this.totalDistricts = totalDistricts;
    }

    /** One province: its ordered city names plus the districts of each city. */
    private static final class Province {
        @NonNull
        final List<String> cities;
        @NonNull
        final Map<String, List<String>> districtsByCity;

        Province(@NonNull Map<String, List<String>> districtsByCity) {
            this.districtsByCity = Collections.unmodifiableMap(districtsByCity);
            this.cities = Collections.unmodifiableList(new ArrayList<>(districtsByCity.keySet()));
        }
    }

    // ---- construction ----

    /**
     * Parses the asset text. Never throws: any malformed document (bad JSON, wrong
     * root type, unsupported version, missing names, duplicate names) yields the
     * empty degraded catalog.
     */
    @NonNull
    public static RegionCatalog fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return DEGRADED;
        }
        try {
            return parse(json);
        } catch (Exception broken) {
            return DEGRADED;
        }
    }

    /** Production entry point: parses {@code assets/regions.json} on a worker thread. */
    @NonNull
    public static RegionCatalog fromAssets(@Nullable Context context) {
        String json;
        try {
            json = readAsset(context == null ? null : context.getAssets(), ASSET_NAME);
        } catch (Exception missing) {
            return DEGRADED; // no asset = picker disabled, never a crash
        }
        return fromJson(json);
    }

    /**
     * @throws Exception when the document violates the schema; the caller degrades.
     */
    @NonNull
    private static RegionCatalog parse(@NonNull String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int version = root.optInt("v", SCHEMA_VERSION);
        if (version != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported regions.json v" + version);
        }
        JSONArray provinceArray = optArray(root, "provinces", "provinces");
        if (provinceArray == null || provinceArray.length() == 0) {
            throw new IllegalStateException("regions.json without provinces");
        }

        Map<String, Province> byName = new LinkedHashMap<>();
        int cities = 0;
        int districts = 0;
        for (int i = 0; i < provinceArray.length(); i++) {
            JSONObject provinceJson = provinceArray.optJSONObject(i);
            String provinceName = requireName(provinceJson, "province");
            if (byName.containsKey(provinceName)) {
                throw new IllegalStateException("duplicate province " + provinceName);
            }
            Map<String, List<String>> districtsByCity = new LinkedHashMap<>();
            JSONArray cityArray = optArray(provinceJson, "cities", "cities");
            if (cityArray != null) {
                for (int j = 0; j < cityArray.length(); j++) {
                    JSONObject cityJson = cityArray.optJSONObject(j);
                    String cityName = requireName(cityJson, "city");
                    if (districtsByCity.containsKey(cityName)) {
                        throw new IllegalStateException(
                                "duplicate city " + provinceName + " " + cityName);
                    }
                    List<String> districtsInCity = new ArrayList<>();
                    JSONArray districtArray = optArray(cityJson, "districts", "districts");
                    if (districtArray != null) {
                        for (int k = 0; k < districtArray.length(); k++) {
                            Object raw = districtArray.opt(k);
                            if (!(raw instanceof String)) {
                                throw new IllegalStateException("district name must be a string");
                            }
                            String district = ((String) raw).trim();
                            if (!district.isEmpty()) {
                                districtsInCity.add(district);
                            }
                        }
                    }
                    districts += districtsInCity.size();
                    districtsByCity.put(cityName,
                            Collections.unmodifiableList(districtsInCity));
                }
            }
            cities += districtsByCity.size();
            byName.put(provinceName, new Province(districtsByCity));
        }
        return new RegionCatalog(Collections.unmodifiableMap(byName), false, cities, districts);
    }

    @NonNull
    private static String requireName(@Nullable JSONObject json, @NonNull String what) {
        if (json == null) {
            throw new IllegalStateException("missing " + what + " entry");
        }
        Object raw = json.opt("n");
        if (!(raw instanceof String)) {
            throw new IllegalStateException(what + " without a name");
        }
        String name = ((String) raw).trim();
        if (name.isEmpty()) {
            throw new IllegalStateException(what + " without a name");
        }
        return name;
    }

    /** Present-but-wrong-type arrays are a schema violation; absent is tolerated. */
    @Nullable
    private static JSONArray optArray(@NonNull JSONObject json, @NonNull String key,
                                     @NonNull String what) {
        Object raw = json.opt(key);
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (!(raw instanceof JSONArray)) {
            throw new IllegalStateException(what + " must be an array");
        }
        return (JSONArray) raw;
    }

    @NonNull
    private static String readAsset(@Nullable AssetManager assets, @NonNull String name)
            throws IOException {
        if (assets == null) {
            throw new IOException("no AssetManager");
        }
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- queries (never throw) ----

    /** All province-level names in official code order. */
    @NonNull
    public List<String> provinces() {
        return provinceNames;
    }

    /** Cities of a province; empty list for {@code null}, blank or unknown input. */
    @NonNull
    public List<String> cities(@Nullable String province) {
        Province node = lookup(province);
        return node == null ? Collections.<String>emptyList() : node.cities;
    }

    /** Districts of a city; empty list for {@code null}, blank or unknown input. */
    @NonNull
    public List<String> districts(@Nullable String province, @Nullable String city) {
        Province node = lookup(province);
        if (node == null || city == null) {
            return Collections.emptyList();
        }
        List<String> districts = node.districtsByCity.get(city.trim());
        return districts == null ? Collections.<String>emptyList() : districts;
    }

    @Nullable
    private Province lookup(@Nullable String province) {
        return province == null ? null : provincesByName.get(province.trim());
    }

    /**
     * False only when the bundled asset parsed. The settings screen disables the
     * picker and shows an inline hint instead of pretending a region can be chosen.
     */
    public boolean isDegraded() {
        return degraded;
    }

    /** Districts across the whole country; 0 when degraded. */
    public int totalDistricts() {
        return totalDistricts;
    }

    /** Cities/prefectures across the whole country; 0 when degraded. */
    public int totalCities() {
        return totalCities;
    }

    /** Province count as a primitive, for tests and log lines. */
    public int totalProvinces() {
        return provinceNames.size();
    }

    /**
     * Canonical display path, e.g. {@code 浙江省 杭州市 西湖区} or, for a
     * municipality, {@code 北京市 朝阳区}. Blank/duplicate parts collapse, so
     * {@code displayName("北京市", "北京市", "")} is simply {@code 北京市}.
     */
    @NonNull
    public static String displayName(@Nullable String province, @Nullable String city,
                                     @Nullable String district) {
        return Region.of(province, city, district).displayName();
    }
}
