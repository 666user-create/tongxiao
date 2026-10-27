package com.course.tongxiao.engine.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Three-level region directory (design.md §7 ⑤): the bundled asset, the defensive
 * parser and the query contract.
 *
 * <p>Facts asserted against the shipped {@code assets/regions.json} — built from the
 * NBS 2023 statistical division codes (see the asset's own {@code src}/{@code fetched}
 * fields) — are real samples, so the test fails loudly if the data is ever truncated,
 * reordered or re-encoded. Counts are pinned to this asset version on purpose: a
 * lower number means districts went missing, which is the failure this class exists
 * to prevent.
 *
 * <p>Numbers: 34 province-level divisions - 31 from the NBS statistical code set
 * (mainland) plus Hong Kong / Macao / Taiwan appended by us, 365 cities/prefectures
 * (municipalities collapsed, so 重庆's two pseudo levels became one; Taiwan is city
 * level only) and 3094 district/county units (Taipei's 12 districts included, the
 * other Taiwan cities have none).
 */
@RunWith(AndroidJUnit4.class)
public class RegionCatalogTest {

    private static final int EXPECTED_PROVINCES = 34;
    private static final int EXPECTED_CITIES = 365;
    private static final int EXPECTED_DISTRICTS = 3094;

    /** Every mainland province-level division, in official code order. */
    private static final String[] ALL_PROVINCES = {
            "北京市", "天津市", "河北省", "山西省", "内蒙古自治区",
            "辽宁省", "吉林省", "黑龙江省", "上海市", "江苏省",
            "浙江省", "安徽省", "福建省", "江西省", "山东省",
            "河南省", "湖北省", "湖南省", "广东省", "广西壮族自治区",
            "海南省", "重庆市", "四川省", "贵州省", "云南省",
            "西藏自治区", "陕西省", "甘肃省", "青海省", "宁夏回族自治区",
            "新疆维吾尔自治区", "香港特别行政区", "澳门特别行政区", "台湾省",
    };

    private RegionCatalog catalog;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        catalog = RegionCatalog.fromAssets(context);
    }

    // ---- the bundled asset ----

    @Test
    public void asset_parsesIntoTheThreeLevelDirectory() {
        assertFalse("bundled regions.json must parse", catalog.isDegraded());
        assertEquals("province-level divisions", EXPECTED_PROVINCES, catalog.provinces().size());
        assertEquals("cities/prefectures", EXPECTED_CITIES, catalog.totalCities());
        assertEquals("districts/counties", EXPECTED_DISTRICTS, catalog.totalDistricts());
        assertTrue("real-country scale, not a hand-picked sample",
                catalog.totalDistricts() > 2000);
    }

    @Test
    public void asset_listsEveryMainlandProvinceInCodeOrder() {
        assertEquals(EXPECTED_PROVINCES, catalog.provinces().size());
        assertEquals("first province", "北京市", catalog.provinces().get(0));
        assertEquals("last province", "台湾省",
                catalog.provinces().get(catalog.provinces().size() - 1));
        Set<String> present = new HashSet<>(catalog.provinces());
        for (String province : ALL_PROVINCES) {
            assertTrue(province + " missing from the directory", present.contains(province));
        }
        assertEquals("no duplicate province rows", ALL_PROVINCES.length, present.size());
    }

    @Test
    public void asset_zhejiangHangzhouSample() {
        List<String> cities = catalog.cities("浙江省");
        assertEquals(11, cities.size());
        assertTrue("杭州市", cities.contains("杭州市"));

        List<String> districts = catalog.districts("浙江省", "杭州市");
        assertEquals("杭州市 district count", 13, districts.size());
        assertTrue("西湖区", districts.contains("西湖区"));
        assertTrue("滨江区", districts.contains("滨江区"));
        assertTrue("临安区", districts.contains("临安区"));
    }

    @Test
    public void asset_beijingMunicipality_keepsProvinceThenDistrictShape() {
        // A municipality is its own city: 省级 == 市级, so the picker still has two
        // moving parts instead of a fake prefecture called 市辖区.
        assertEquals(Arrays.asList("北京市"), catalog.cities("北京市"));
        List<String> districts = catalog.districts("北京市", "北京市");
        assertEquals(16, districts.size());
        assertTrue(districts.contains("朝阳区"));
        assertTrue(districts.contains("海淀区"));
        assertTrue("远郊 included", districts.contains("延庆区"));
    }

    @Test
    public void asset_sichuanChengduSample() {
        assertEquals(21, catalog.cities("四川省").size());
        List<String> districts = catalog.districts("四川省", "成都市");
        assertEquals(20, districts.size());
        assertTrue("武侯区", districts.contains("武侯区"));
        assertTrue("都江堰市", districts.contains("都江堰市"));
    }

    @Test
    public void asset_chongqing_urbanAndCountyLevelsMergedUnderOneCity() {
        // 重庆 is split into 市辖区 + 县 in the raw statistics; both belong to the
        // single "重庆市" city the user sees.
        assertEquals(Arrays.asList("重庆市"), catalog.cities("重庆市"));
        List<String> districts = catalog.districts("重庆市", "重庆市");
        assertEquals("26 区 + 12 县", 38, districts.size());
        assertTrue("urban district", districts.contains("渝中区"));
        assertTrue("county", districts.contains("城口县"));
    }

    @Test
    public void asset_provinciallyAdministeredCountiesStayUnderTheirRealLabel() {
        // 济源市 etc. hang off the official "省直辖县级行政区划" slot. The label is
        // ugly on purpose: it is the real division name, not something invented here.
        List<String> districts = catalog.districts("河南省", "省直辖县级行政区划");
        assertEquals(1, districts.size());
        assertTrue(districts.contains("济源市"));
    }

    @Test
    public void asset_namesAreCleanEnoughForTheStoredPath() {
        // WeatherClient splits the saved path on whitespace, so no name may contain
        // any: a space inside a district would silently break coordinate lookup.
        int districtsSeen = 0;
        int citiesWithoutDistricts = 0;
        for (String province : catalog.provinces()) {
            assertFalse(province.trim().isEmpty());
            List<String> cities = catalog.cities(province);
            assertFalse(province + " has no cities", cities.isEmpty());
            for (String city : cities) {
                assertFalse(city.trim().isEmpty());
                List<String> districts = catalog.districts(province, city);
                if (districts.isEmpty()) {
                    // Allowed for Taiwan's non-Taipei cities: the district level is
                    // not bundled, the picker hides the third wheel and weather
                    // resolves by city name. Tracked so a mainland regression (all
                    // districts gone) would still fail the count below.
                    citiesWithoutDistricts++;
                    continue;
                }
                for (String district : districts) {
                    assertFalse("blank district in " + province + " " + city,
                            district.trim().isEmpty());
                    assertEquals("whitespace in district name", district, district.trim());
                    assertFalse("space inside district name " + district,
                            district.contains(" "));
                    districtsSeen++;
                }
            }
        }
        assertEquals(EXPECTED_DISTRICTS, districtsSeen);
        assertEquals("only Taiwan's non-Taipei cities may lack a district level",
                21, citiesWithoutDistricts);
    }

    // ---- queries must never blow up ----

    @Test
    public void query_unknownAndBlankInputReturnsEmptyLists() {
        assertTrue(catalog.cities("不存在省").isEmpty());
        assertTrue(catalog.cities(null).isEmpty());
        assertTrue(catalog.cities("").isEmpty());
        assertTrue(catalog.cities("   ").isEmpty());

        assertTrue(catalog.districts("浙江省", "不存在市").isEmpty());
        assertTrue(catalog.districts("不存在省", "杭州市").isEmpty());
        assertTrue(catalog.districts(null, "杭州市").isEmpty());
        assertTrue(catalog.districts("浙江省", null).isEmpty());
        assertTrue(catalog.districts(null, null).isEmpty());
        assertTrue(catalog.districts("", "").isEmpty());
        // A district reached through the wrong level is not silently guessed at.
        assertTrue(catalog.districts("北京市", "朝阳区").isEmpty());
        // And a real city under the wrong province does not leak.
        assertTrue(catalog.districts("浙江省", "成都市").isEmpty());
    }

    @Test
    public void query_toleratesSurroundingWhitespace() {
        assertEquals(11, catalog.cities(" 浙江省 ").size());
        assertEquals(13, catalog.districts(" 浙江省 ", " 杭州市 ").size());
    }

    @Test
    public void query_listsAreImmutableSoCallersCannotCorruptTheCatalog() {
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.provinces().add("火星省"));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.cities("浙江省").add("火星市"));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.districts("浙江省", "杭州市").add("火星区"));
        assertEquals("catalog unchanged after failed mutation",
                EXPECTED_PROVINCES, catalog.provinces().size());
    }

    // ---- defensive parsing: broken data degrades, never crashes ----

    @Test
    public void badJson_degradesToAnEmptyCatalogInsteadOfThrowing() {
        String[] broken = {
                "{",                        // truncated
                "[]",                       // wrong root type
                "null",                     // JSON null
                "",                         // empty file
                "   ",                      // whitespace only
                "{\"provinces\":[{}]}",     // province without a name
                "{\"provinces\":[]}",       // empty directory
                "{\"v\":1,\"provinces\":\"nope\"}",          // wrong type
                "{\"v\":1,\"provinces\":[{\"n\":\"X\",\"cities\":\"nope\"}]}",
                "{\"v\":1,\"provinces\":[{\"n\":123,\"cities\":[]}]}",
                "not json at all",
        };
        for (String json : broken) {
            RegionCatalog degraded = RegionCatalog.fromJson(json);
            assertTrue("must degrade: " + json, degraded.isDegraded());
            assertTrue(degraded.provinces().isEmpty());
            assertEquals(0, degraded.totalDistricts());
            assertEquals(0, degraded.totalCities());
            // Queries on a degraded catalog are harmless, not throwing.
            assertTrue(degraded.cities("浙江省").isEmpty());
            assertTrue(degraded.districts("浙江省", "杭州市").isEmpty());
            assertTrue(degraded.cities(null).isEmpty());
        }
    }

    @Test
    public void badJson_futureSchemaVersionDegradesRatherThanMisreading() {
        String future = "{\"v\":2,\"provinces\":[{\"n\":\"省A\","
                + "\"cities\":[{\"n\":\"市A\",\"districts\":[\"区A\"]}]}]}";
        RegionCatalog degraded = RegionCatalog.fromJson(future);
        assertTrue("unknown schema version", degraded.isDegraded());
        assertTrue(degraded.provinces().isEmpty());
    }

    @Test
    public void badJson_duplicateNamesDegradeInsteadOfOverwriting() {
        String duplicated = "{\"v\":1,\"provinces\":["
                + "{\"n\":\"省A\",\"cities\":[{\"n\":\"市A\",\"districts\":[\"区A\"]}]},"
                + "{\"n\":\"省A\",\"cities\":[{\"n\":\"市B\",\"districts\":[\"区B\"]}]}]}";
        assertTrue(RegionCatalog.fromJson(duplicated).isDegraded());
    }

    @Test
    public void syntheticDocument_parsesWithoutTheAsset() {
        String json = "{\"v\":1,\"provinces\":[{\"n\":\"测试省\",\"cities\":["
                + "{\"n\":\"测试市\",\"districts\":[\"甲区\",\"乙区\"]},"
                + "{\"n\":\"空市\",\"districts\":[]}]}]}";
        RegionCatalog small = RegionCatalog.fromJson(json);

        assertFalse("a well-formed document must not degrade", small.isDegraded());
        assertEquals(Arrays.asList("测试省"), small.provinces());
        assertEquals(Arrays.asList("测试市", "空市"), small.cities("测试省"));
        assertEquals(Arrays.asList("甲区", "乙区"), small.districts("测试省", "测试市"));
        // A city without districts keeps the picker working (the district wheel hides).
        assertTrue(small.districts("测试省", "空市").isEmpty());
        assertEquals(2, small.totalCities());
        assertEquals(2, small.totalDistricts());
        assertEquals(1, small.totalProvinces());
    }

    // ---- display name ----

    @Test
    public void displayName_joinsTheThreeLevels() {
        assertEquals("浙江省 杭州市 西湖区",
                RegionCatalog.displayName("浙江省", "杭州市", "西湖区"));
        assertEquals("四川省 成都市 武侯区",
                RegionCatalog.displayName("四川省", "成都市", "武侯区"));
        // Municipality: the repeated city collapses, matching the user's mental model.
        assertEquals("北京市 朝阳区", RegionCatalog.displayName("北京市", "北京市", "朝阳区"));
        assertEquals("上海市 浦东新区", RegionCatalog.displayName("上海市", "上海市", "浦东新区"));
        // Partially filled paths stay short.
        assertEquals("北京市", RegionCatalog.displayName("北京市", "北京市", ""));
        assertEquals("浙江省 杭州市", RegionCatalog.displayName("浙江省", "杭州市", ""));
        assertEquals("浙江省", RegionCatalog.displayName("浙江省", "", ""));
    }

    @Test
    public void displayName_isBlankSafe() {
        assertEquals("", RegionCatalog.displayName(null, null, null));
        assertEquals("", RegionCatalog.displayName("", "", ""));
        assertEquals("", RegionCatalog.displayName("   ", "  ", " "));
        assertEquals("浙江省", RegionCatalog.displayName(" 浙江省 ", null, null));
        assertEquals("浙江省 杭州市 西湖区",
                RegionCatalog.displayName(" 浙江省 ", " 杭州市 ", " 西湖区 "));
    }

    @Test
    public void displayName_isTheWhitespacePathWeatherClientSplits() {
        // Contract with WeatherClient: split on whitespace, most specific part LAST.
        String[] parts = RegionCatalog.displayName("浙江省", "杭州市", "西湖区").split("\\s+");
        assertEquals(3, parts.length);
        assertEquals("浙江省", parts[0]);
        assertEquals("杭州市", parts[1]);
        assertEquals("西湖区", parts[2]);

        String[] municipality = RegionCatalog.displayName("北京市", "北京市", "朝阳区")
                .split("\\s+");
        assertEquals(2, municipality.length);
        assertEquals("北京市", municipality[0]);
        assertEquals("朝阳区", municipality[1]);
    }

    // ---- Region value object ----

    @Test
    public void region_reportsItsLevel() {
        Region full = Region.of("浙江省", "杭州市", "西湖区");
        assertEquals(Region.Level.DISTRICT, full.level());
        assertTrue(full.isComplete());
        assertEquals("浙江省 杭州市 西湖区", full.displayName());

        Region cityOnly = Region.of("浙江省", "杭州市");
        assertEquals(Region.Level.CITY, cityOnly.level());
        assertFalse(cityOnly.isComplete());
        assertEquals("浙江省 杭州市", cityOnly.displayName());

        Region provinceOnly = Region.of("浙江省");
        assertEquals(Region.Level.PROVINCE, provinceOnly.level());
        assertEquals("", provinceOnly.city());
        assertEquals("", provinceOnly.district());
        assertEquals("浙江省", provinceOnly.displayName());

        Region empty = Region.of(null, null, null);
        assertEquals(Region.Level.PROVINCE, empty.level());
        assertEquals("", empty.displayName());
    }

    @Test
    public void region_equalityAndDisplayMatchTheCatalogHelper() {
        Region a = Region.of("浙江省", "杭州市", "西湖区");
        Region b = Region.of(" 浙江省 ", "杭州市", "西湖区 ");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(Region.of("浙江省", "杭州市", "滨江区")));
        assertEquals(RegionCatalog.displayName("浙江省", "杭州市", "西湖区"), a.displayName());
        assertNotNull(a.toString());
        assertTrue(a.toString().contains("西湖区"));
    }

    // ---- Hong Kong / Macao / Taiwan (appended on top of the NBS mainland set) ----

    @Test
    public void hongKong_hasIts18DistrictsUnderOneCity() {
        assertEquals(Arrays.asList("香港特别行政区"), catalog.cities("香港特别行政区"));
        List<String> districts = catalog.districts("香港特别行政区", "香港特别行政区");
        assertEquals(18, districts.size());
        assertTrue(districts.contains("中西区"));
        assertTrue(districts.contains("沙田区"));
    }

    @Test
    public void macao_hasIts8Parishes() {
        List<String> districts = catalog.districts("澳门特别行政区", "澳门特别行政区");
        assertEquals(8, districts.size());
        assertTrue(districts.contains("花地玛堂区"));
        assertTrue(districts.contains("路氹填海区"));
    }

    @Test
    public void taiwan_listsIts22CountyLevelCities() {
        List<String> cities = catalog.cities("台湾省");
        assertEquals(22, cities.size());
        assertTrue(cities.contains("台北市"));
        assertTrue(cities.contains("高雄市"));
        assertTrue(cities.contains("连江县"));
    }

    @Test
    public void taiwan_taipeiHasDistricts_otherCitiesKeepThePickerAtTwoLevels() {
        List<String> taipei = catalog.districts("台湾省", "台北市");
        assertEquals(12, taipei.size());
        assertTrue(taipei.contains("中正区"));
        assertTrue(taipei.contains("文山区"));

        // No district data for the rest: the picker hides the third wheel instead
        // of inventing a level, and weather resolves by city name.
        assertTrue(catalog.districts("台湾省", "高雄市").isEmpty());
        assertEquals("台湾省 高雄市", RegionCatalog.displayName("台湾省", "高雄市", ""));
    }

    @Test
    public void displayName_coversTheAppendedProvinces() {
        // Hong Kong / Macao fold like municipalities: province == city, so the path
        // is two segments (same rule as 北京市 朝阳区).
        assertEquals("香港特别行政区 中西区",
                RegionCatalog.displayName("香港特别行政区", "香港特别行政区", "中西区"));
        assertEquals("澳门特别行政区 大堂区",
                RegionCatalog.displayName("澳门特别行政区", "澳门特别行政区", "大堂区"));
        assertEquals("台湾省 台北市 中正区",
                RegionCatalog.displayName("台湾省", "台北市", "中正区"));
    }
}
