package com.course.tongxiao.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.R;
import com.course.tongxiao.engine.region.RegionCatalog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-wheel region picker: province → city → district (design.md §7 ⑤).
 *
 * <p>Why wheels and not a text field: a free-text city name is both error prone
 * ("北京" vs "北京市") and too coarse for weather (a municipality is huge). The
 * three-level picker keeps the value canonical and precise.
 *
 * <p>The district list can be empty for some cities in the dataset; the third
 * wheel is then hidden and the saved value is "province city".
 */
public final class RegionPickerDialog {

    /** @param displayName canonical space-separated path, e.g. {@code 浙江省 杭州市 西湖区}. */
    public interface OnRegionPicked {
        void onPicked(@NonNull String displayName);
    }

    private RegionPickerDialog() {
    }

    /**
     * @param catalog        region data; when degraded the dialog is not shown
     * @param currentRegion  previously saved value (used to pre-select the wheels)
     */
    public static void show(@NonNull Context context, @NonNull RegionCatalog catalog,
                            @Nullable String currentRegion,
                            @NonNull OnRegionPicked callback) {
        List<String> provinces = catalog.provinces();
        if (provinces.isEmpty()) {
            return; // degraded catalog: the caller shows the inline hint instead
        }

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_region_picker, null);
        NumberPicker provincePicker = content.findViewById(R.id.picker_province);
        NumberPicker cityPicker = content.findViewById(R.id.picker_city);
        NumberPicker districtPicker = content.findViewById(R.id.picker_district);

        // Restore the previous selection so re-opening the dialog is not a reset.
        String[] previous = currentRegion == null
                ? new String[0] : currentRegion.trim().split("\\s+");
        int[] restored = resolveIndices(catalog, previous);
        int provinceIndex = restored[0];
        int cityIndex = restored[1];
        int districtIndex = restored[2];

        List<String> cities = catalog.cities(provinces.get(provinceIndex));
        List<String> districts = cities.isEmpty()
                ? new ArrayList<>() : catalog.districts(provinces.get(provinceIndex),
                cities.get(cityIndex));

        provincePicker.setDisplayedValues(provinces.toArray(new String[0]));
        provincePicker.setMinValue(0);
        provincePicker.setMaxValue(provinces.size() - 1);
        provincePicker.setValue(provinceIndex);
        provincePicker.setWrapSelectorWheel(false);

        bindList(cityPicker, cities, cityIndex);
        bindList(districtPicker, districts, districtIndex);
        districtPicker.setVisibility(districts.isEmpty() ? View.GONE : View.VISIBLE);

        provincePicker.setOnValueChangedListener((picker, oldValue, newValue) -> {
            List<String> newCities = catalog.cities(provinces.get(newValue));
            bindList(cityPicker, newCities, 0);
            List<String> newDistricts = newCities.isEmpty()
                    ? new ArrayList<>() : catalog.districts(provinces.get(newValue), newCities.get(0));
            bindList(districtPicker, newDistricts, 0);
            districtPicker.setVisibility(newDistricts.isEmpty() ? View.GONE : View.VISIBLE);
        });

        cityPicker.setOnValueChangedListener((picker, oldValue, newValue) -> {
            List<String> newDistricts = catalog.districts(
                    provinces.get(provincePicker.getValue()),
                    citiesAt(catalog, provinces.get(provincePicker.getValue()), newValue));
            bindList(districtPicker, newDistricts, 0);
            districtPicker.setVisibility(newDistricts.isEmpty() ? View.GONE : View.VISIBLE);
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_region_title)
                .setView(content)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) -> {
                    String province = provinces.get(provincePicker.getValue());
                    List<String> chosenCities = catalog.cities(province);
                    String city = chosenCities.isEmpty()
                            ? "" : chosenCities.get(Math.min(cityPicker.getValue(),
                            chosenCities.size() - 1));
                    List<String> chosenDistricts = city.isEmpty()
                            ? new ArrayList<>() : catalog.districts(province, city);
                    String district = chosenDistricts.isEmpty()
                            ? "" : chosenDistricts.get(Math.min(districtPicker.getValue(),
                            chosenDistricts.size() - 1));
                    callback.onPicked(RegionCatalog.displayName(province, city, district));
                })
                .show();
    }

    /**
     * Maps a stored region path back to wheel indices.
     *
     * <p>Municipalities are stored as two segments ({@code 北京市 朝阳区}) while
     * provinces are three ({@code 浙江省 杭州市 西湖区}), so the second segment is
     * either a city or already a district. Treating it blindly as a city reset the
     * district wheel to the first entry whenever the dialog was reopened.
     *
     * @return {@code {provinceIndex, cityIndex, districtIndex}}; 0 when unknown
     */
    @NonNull
    static int[] resolveIndices(@NonNull RegionCatalog catalog, @NonNull String[] path) {
        List<String> provinces = catalog.provinces();
        int provinceIndex = 0;
        if (path.length > 0) {
            int found = provinces.indexOf(path[0]);
            provinceIndex = found < 0 ? 0 : found;
        }
        if (provinces.isEmpty()) {
            return new int[]{0, 0, 0};
        }

        List<String> cities = catalog.cities(provinces.get(provinceIndex));
        int cityIndex = 0;
        String districtName = null;
        if (path.length > 1) {
            int cityFound = cities.indexOf(path[1]);
            if (cityFound >= 0) {
                cityIndex = cityFound;
                if (path.length > 2) {
                    districtName = path[2];
                }
            } else {
                // Two-segment path: the second segment is the district.
                districtName = path[1];
            }
        }

        int districtIndex = 0;
        if (districtName != null && !cities.isEmpty()) {
            List<String> districts = catalog.districts(provinces.get(provinceIndex),
                    cities.get(cityIndex));
            int found = districts.indexOf(districtName);
            if (found >= 0) {
                districtIndex = found;
            }
        }
        return new int[]{provinceIndex, cityIndex, districtIndex};
    }

    /** Re-reads a city name by index; keeps the value listener free of stale lists. */
    @NonNull
    private static String citiesAt(@NonNull RegionCatalog catalog, @NonNull String province,
                                   int index) {
        List<String> cities = catalog.cities(province);
        if (cities.isEmpty()) {
            return "";
        }
        return cities.get(Math.max(0, Math.min(index, cities.size() - 1)));
    }

    private static void bindList(@NonNull NumberPicker picker, @NonNull List<String> values,
                                 int selectedIndex) {
        if (values.isEmpty()) {
            picker.setDisplayedValues(null);
            picker.setMinValue(0);
            picker.setMaxValue(0);
            picker.setValue(0);
            return;
        }
        picker.setDisplayedValues(values.toArray(new String[0]));
        picker.setMinValue(0);
        picker.setMaxValue(values.size() - 1);
        picker.setValue(Math.max(0, Math.min(selectedIndex, values.size() - 1)));
        picker.setWrapSelectorWheel(false);
    }
}
