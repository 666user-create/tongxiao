package com.course.tongxiao.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.course.tongxiao.R;
import com.course.tongxiao.engine.region.RegionCatalog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Three-wheel region picker: province → city → district (design.md §7 ⑤).
 *
 * <p>Why wheels and not a text field: a free-text city name is both error prone
 * ("北京" vs "北京市") and too coarse for weather (a municipality is huge). The
 * three-level picker keeps the value canonical and precise.
 *
 * <p>All wheel binding lives in {@link RegionWheelController} so it is testable
 * without showing a dialog; this class only assembles the UI.
 */
public final class RegionPickerDialog {

    /** @param displayName canonical space-separated path, e.g. {@code 浙江省 杭州市 西湖区}. */
    public interface OnRegionPicked {
        void onPicked(@NonNull String displayName);
    }

    private RegionPickerDialog() {
    }

    /**
     * @param catalog        region data; when degraded nothing is shown
     * @param currentRegion  previously saved value (used to pre-select the wheels)
     * @return the dialog, or null when the catalog is unusable
     */
    @Nullable
    public static AlertDialog show(@NonNull Context context, @NonNull RegionCatalog catalog,
                                   @Nullable String currentRegion,
                                   @NonNull OnRegionPicked callback) {
        if (catalog.provinces().isEmpty()) {
            return null; // degraded catalog: the caller shows the inline hint instead
        }

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_region_picker, null);
        NumberPicker provinceWheel = content.findViewById(R.id.picker_province);
        NumberPicker cityWheel = content.findViewById(R.id.picker_city);
        NumberPicker districtWheel = content.findViewById(R.id.picker_district);

        RegionWheelController controller = new RegionWheelController(
                catalog, provinceWheel, cityWheel, districtWheel);
        controller.bind(currentRegion);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_region_title)
                .setView(content)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, which) ->
                        callback.onPicked(controller.selectedPath()))
                .create();
        dialog.show();
        return dialog;
    }

    /**
     * Maps a stored region path back to wheel indices.
     *
     * <p>Municipalities and special regions are stored as two segments
     * ({@code 北京市 朝阳区}) while provinces use three, so the second segment is
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
}
