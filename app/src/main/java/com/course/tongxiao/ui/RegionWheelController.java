package com.course.tongxiao.ui;

import android.view.View;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.engine.region.RegionCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Binding logic for the three region wheels, kept out of the dialog so it can be
 * driven by tests without showing UI.
 *
 * <p>Two invariants matter here:
 * <ul>
 *   <li>a wheel's displayed values array always matches {@code max - min + 1}
 *       (Android indexes that array by value, so rebinding values before bounds
 *       throws when a list shrinks);</li>
 *   <li>the cascade never relies on {@link NumberPicker#setValue} to notify:
 *       programmatic {@code setValue} does <b>not</b> fire
 *       {@code OnValueChangedListener} (only user scrolling does), so the wheels
 *       are re-bound explicitly by {@link #selectProvince}/{@link #selectCity}.</li>
 * </ul>
 */
public final class RegionWheelController {

    private final RegionCatalog catalog;
    private final NumberPicker provinceWheel;
    private final NumberPicker cityWheel;
    private final NumberPicker districtWheel;

    private List<String> provinces = new ArrayList<>();
    private List<String> cities = new ArrayList<>();
    private List<String> districts = new ArrayList<>();

    /** Guards against re-entrancy when a picker notifies while being re-bound. */
    private boolean cascading;

    public RegionWheelController(@NonNull RegionCatalog catalog,
                                 @NonNull NumberPicker provinceWheel,
                                 @NonNull NumberPicker cityWheel,
                                 @NonNull NumberPicker districtWheel) {
        this.catalog = catalog;
        this.provinceWheel = provinceWheel;
        this.cityWheel = cityWheel;
        this.districtWheel = districtWheel;
    }

    /** Fills the wheels and restores {@code path} (a space separated region path). */
    public void bind(@Nullable String path) {
        provinces = catalog.provinces();
        int[] restored = RegionPickerDialog.resolveIndices(catalog,
                path == null ? new String[0] : path.trim().split("\\s+"));
        bindValues(provinceWheel, provinces, restored[0]);
        cities = catalog.cities(selectedProvince());
        bindValues(cityWheel, cities, restored[1]);
        districts = currentDistricts();
        bindValues(districtWheel, districts, restored[2]);

        provinceWheel.setOnValueChangedListener((picker, oldValue, newValue) -> {
            if (!cascading) {
                selectProvince(newValue);
            }
        });
        cityWheel.setOnValueChangedListener((picker, oldValue, newValue) -> {
            if (!cascading) {
                selectCity(newValue);
            }
        });
        // The district wheel has no dependants, so it deliberately has NO listener:
        // re-binding a wheel to itself on every scroll resets its displayed values
        // (and the snap animation) for no reason.
    }

    /** Selects a province and resets the two dependent wheels to their first entry. */
    public void selectProvince(int index) {
        cascading = true;
        try {
            bindValues(provinceWheel, provinces, index);
            cities = catalog.cities(selectedProvince());
            bindValues(cityWheel, cities, 0);
            districts = currentDistricts();
            bindValues(districtWheel, districts, 0);
        } finally {
            cascading = false;
        }
    }

    /** Selects a city and resets the district wheel to its first entry. */
    public void selectCity(int index) {
        cascading = true;
        try {
            bindValues(cityWheel, cities, index);
            districts = currentDistricts();
            bindValues(districtWheel, districts, 0);
        } finally {
            cascading = false;
        }
    }

    @NonNull
    public String selectedProvince() {
        return valueAt(provinces, provinceWheel.getValue());
    }

    @NonNull
    public String selectedCity() {
        return valueAt(cities, cityWheel.getValue());
    }

    @NonNull
    public String selectedDistrict() {
        return valueAt(districts, districtWheel.getValue());
    }

    /** Canonical path, e.g. {@code 浙江省 杭州市 西湖区} or {@code 台湾省 高雄市}. */
    @NonNull
    public String selectedPath() {
        return RegionCatalog.displayName(selectedProvince(), selectedCity(), selectedDistrict());
    }

    public int districtCount() {
        return districts.size();
    }

    public int cityCount() {
        return cities.size();
    }

    private List<String> currentDistricts() {
        String province = selectedProvince();
        String city = selectedCity();
        if (province.isEmpty() || city.isEmpty()) {
            return new ArrayList<>();
        }
        return catalog.districts(province, city);
    }

    /**
     * Rebinds one wheel safely: bounds first, values second, value last; the wheel
     * hides itself when there is nothing to show.
     */
    private static void bindValues(@NonNull NumberPicker picker, @NonNull List<String> values,
                                   int selected) {
        int max = Math.max(0, values.size() - 1);
        picker.setDisplayedValues(null);
        picker.setMinValue(0);
        picker.setMaxValue(max);
        if (!values.isEmpty()) {
            picker.setDisplayedValues(values.toArray(new String[0]));
        }
        picker.setValue(Math.max(0, Math.min(selected, max)));
        picker.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static int clamp(int index, @NonNull List<String> values) {
        return Math.max(0, Math.min(index, Math.max(0, values.size() - 1)));
    }

    @NonNull
    private static String valueAt(@NonNull List<String> values, int index) {
        if (values.isEmpty()) {
            return "";
        }
        return values.get(clamp(index, values));
    }
}
