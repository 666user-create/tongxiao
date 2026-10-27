package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.NumberPicker;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.engine.region.RegionCatalog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Crash hunt for the region wheels: drives every province (and up to three cities
 * each) through real {@link NumberPicker} instances.
 *
 * <p>This is the regression test for the crash that wheel rebinding causes when a
 * list shrinks — going from 浙江省 杭州市 (13 districts) to 台湾省 高雄市 (0) used
 * to leave the displayed-values array shorter than {@code max - min + 1}, which
 * Android then indexed out of bounds.
 */
@RunWith(AndroidJUnit4.class)
public class RegionWheelControllerTest {

    private Context context;
    private RegionCatalog catalog;
    private NumberPicker provinceWheel;
    private NumberPicker cityWheel;
    private NumberPicker districtWheel;
    private RegionWheelController controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        catalog = RegionCatalog.fromAssets(context);
        assertFalse("bundled catalog must parse", catalog.isDegraded());
        provinceWheel = new NumberPicker(context);
        cityWheel = new NumberPicker(context);
        districtWheel = new NumberPicker(context);
        controller = new RegionWheelController(catalog, provinceWheel, cityWheel, districtWheel);
        controller.bind(null);
    }

    @Test
    public void initialBind_showsTheFirstProvinceAndItsFirstDistrict() {
        assertEquals("北京市", controller.selectedProvince());
        assertFalse("wheels start bound", controller.districtCount() == 0);
        assertTrue(controller.selectedPath().startsWith("北京市"));
    }

    @Test
    public void everyProvinceAndSeveralCities_rebindWithoutThrowing() {
        List<String> provinces = catalog.provinces();
        assertTrue("expect the bundled 34 province-level divisions", provinces.size() >= 34);
        int switches = 0;
        for (int p = 0; p < provinces.size(); p++) {
            controller.selectProvince(p);
            assertEquals(provinces.get(p), controller.selectedProvince());
            List<String> cities = catalog.cities(provinces.get(p));
            for (int c = 0; c < Math.min(cities.size(), 3); c++) {
                controller.selectCity(c);
                assertEquals(cities.get(c), controller.selectedCity());
                switches++;
            }
        }
        assertTrue("should have exercised many city rebinds", switches > 60);
    }

    @Test
    public void shrinkingDistrictList_hidesTheWheelInsteadOfCrashing() {
        controller.selectProvince(catalog.provinces().indexOf("浙江省"));
        controller.selectCity(catalog.cities("浙江省").indexOf("杭州市"));
        assertTrue("Hangzhou has districts", controller.districtCount() > 5);

        // Taiwan's non-Taipei cities have no district level in the asset.
        controller.selectProvince(catalog.provinces().indexOf("台湾省"));
        controller.selectCity(catalog.cities("台湾省").indexOf("高雄市"));
        assertEquals(0, controller.districtCount());
        assertEquals("台湾省 高雄市", controller.selectedPath());
        assertEquals(View.GONE, districtWheel.getVisibility());

        // ...and coming back to a province with districts re-shows the wheel.
        controller.selectProvince(catalog.provinces().indexOf("河北省"));
        assertTrue(controller.districtCount() > 0);
        assertEquals(View.VISIBLE, districtWheel.getVisibility());
    }

    @Test
    public void taipeiKeepsItsDistricts() {
        controller.selectProvince(catalog.provinces().indexOf("台湾省"));
        controller.selectCity(catalog.cities("台湾省").indexOf("台北市"));
        assertEquals(12, controller.districtCount());
        // district wheel already sits on the first entry after selectCity
        assertEquals("台湾省 台北市 中正区", controller.selectedPath());
    }

    @Test
    public void hongKongAndMacao_areSelectable() {
        controller.selectProvince(catalog.provinces().indexOf("香港特别行政区"));
        assertEquals(18, controller.districtCount());
        // district wheel already sits on the first entry after selectCity
        assertEquals("香港特别行政区 中西区", controller.selectedPath());

        controller.selectProvince(catalog.provinces().indexOf("澳门特别行政区"));
        assertEquals(8, controller.districtCount());
    }

    @Test
    public void rebindRestoresASavedPath() {
        String saved = "浙江省 杭州市 滨江区";
        controller.bind(saved);
        assertEquals("浙江省", controller.selectedProvince());
        assertEquals("杭州市", controller.selectedCity());
        assertEquals("滨江区", controller.selectedDistrict());
        assertEquals(saved, controller.selectedPath());

        // Municipality: two segments, district must be restored not reset.
        controller.bind("北京市 朝阳区");
        assertEquals("北京市", controller.selectedProvince());
        assertEquals("朝阳区", controller.selectedDistrict());

        // Unknown path falls back to the first entry without throwing.
        controller.bind("火星省 火星市 火星区");
        assertNotNull(controller.selectedPath());
        assertFalse(controller.selectedPath().isEmpty());
    }

    @Test
    public void emptyCatalog_degradesToEmptySelection() {
        RegionCatalog broken = RegionCatalog.fromJson("{not json");
        NumberPicker p1 = new NumberPicker(context);
        NumberPicker p2 = new NumberPicker(context);
        NumberPicker p3 = new NumberPicker(context);
        RegionWheelController empty = new RegionWheelController(broken, p1, p2, p3);
        empty.bind("浙江省 杭州市 西湖区");
        assertEquals("", empty.selectedProvince());
        assertEquals("", empty.selectedCity());
        assertEquals("", empty.selectedPath());
        assertEquals(View.GONE, p3.getVisibility());
    }
}
