package com.course.tongxiao.engine.region;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable administrative region path (province → city → district), as chosen in
 * the three-level settings picker (design.md §7 ⑤).
 *
 * <p>Only names are stored, mirroring {@code assets/regions.json}: the bundled data
 * carries no codes, so a region is identified by its name path. The district is
 * the meaningful part for weather — it is the most specific coordinate a user can
 * pick, which is why the picker replaced the old free-text city field.
 *
 * <p>Two levels are legal: a province alone ({@code 浙江省}) and province+city
 * ({@code 浙江省 杭州市}). For municipalities the city repeats the province
 * ({@code 北京市} → {@code 北京市}) and {@link #displayName()} collapses the
 * duplicate, so the value a user sees is {@code 北京市 朝阳区}.
 */
public final class Region {

    /** How many levels of the path are actually filled in. */
    public enum Level {
        PROVINCE,
        CITY,
        DISTRICT
    }

    @NonNull
    private final String province;
    @NonNull
    private final String city;
    @NonNull
    private final String district;

    private Region(@NonNull String province, @NonNull String city, @NonNull String district) {
        this.province = province;
        this.city = city;
        this.district = district;
    }

    /**
     * Builds a path from any combination of names; {@code null} and blank parts are
     * treated as "not chosen" so a caller can pass raw UI/database values safely.
     */
    @NonNull
    public static Region of(@Nullable String province, @Nullable String city,
                            @Nullable String district) {
        return new Region(clean(province), clean(city), clean(district));
    }

    /** Province only, e.g. {@code 浙江省}. */
    @NonNull
    public static Region of(@Nullable String province) {
        return new Region(clean(province), "", "");
    }

    /** Province + city, e.g. {@code 浙江省 杭州市}. */
    @NonNull
    public static Region of(@Nullable String province, @Nullable String city) {
        return new Region(clean(province), clean(city), "");
    }

    @NonNull
    private static String clean(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @NonNull
    public String province() {
        return province;
    }

    /** City / prefecture name, empty when only the province was chosen. */
    @NonNull
    public String city() {
        return city;
    }

    /** District / county name, empty when it was not chosen. */
    @NonNull
    public String district() {
        return district;
    }

    /** Deepest level that is filled in. */
    @NonNull
    public Level level() {
        if (!district.isEmpty()) {
            return Level.DISTRICT;
        }
        return city.isEmpty() ? Level.PROVINCE : Level.CITY;
    }

    /** True when the path reaches a district — the level weather needs. */
    public boolean isComplete() {
        return !district.isEmpty();
    }

    /**
     * Space-separated display path, e.g. {@code 浙江省 杭州市 西湖区} or, for a
     * municipality, {@code 北京市 朝阳区}.
     *
     * <p>The format is a contract with the rest of the app: it is what
     * {@code SettingsStore} persists, what the settings screen shows and what
     * {@code WeatherClient} splits on whitespace to resolve coordinates (the most
     * specific part is last on purpose, so the most precise match can win).
     */
    @NonNull
    public String displayName() {
        StringBuilder sb = new StringBuilder(province);
        appendIfNew(sb, city, province);
        appendIfNew(sb, district, city);
        return sb.toString().trim();
    }

    private static void appendIfNew(@NonNull StringBuilder sb, @NonNull String part,
                                    @NonNull String previous) {
        if (part.isEmpty() || part.equals(previous)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Region)) {
            return false;
        }
        Region that = (Region) other;
        return province.equals(that.province)
                && city.equals(that.city)
                && district.equals(that.district);
    }

    @Override
    public int hashCode() {
        return Objects.hash(province, city, district);
    }

    @NonNull
    @Override
    public String toString() {
        return "Region{" + displayName() + '}';
    }
}
