package com.course.tongxiao.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.course.tongxiao.R;
import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.region.RegionCatalog;
import com.course.tongxiao.engine.weather.WeatherClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings tab (design.md §7 ⑤).
 *
 * <p>Weather region is chosen with a three-level picker (province → city →
 * district) instead of a free-text city: a typed name is both ambiguous and too
 * coarse for weather (a whole municipality shares one forecast). Picking is an
 * explicit action with a visible confirmation, and the current selection is
 * always spelled out on screen.
 */
public class SettingsFragment extends Fragment {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SettingsStore settings;
    private TextView holidayStatus;
    private TextView weatherStatus;
    private TextView regionValue;
    private TextView regionHint;
    private MaterialButton regionPick;
    private MaterialSwitch masterSwitch;

    /** Parsed once per screen; parsing ~200 KB of JSON is not free. */
    private RegionCatalog catalog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = new SettingsStore(requireContext());
        holidayStatus = view.findViewById(R.id.settings_holiday_status);
        weatherStatus = view.findViewById(R.id.weather_status);
        regionValue = view.findViewById(R.id.settings_region_value);
        regionHint = view.findViewById(R.id.settings_region_hint);
        regionPick = view.findViewById(R.id.settings_region_pick);
        masterSwitch = view.findViewById(R.id.settings_group_master);

        masterSwitch.setChecked(settings.isGroupMasterSwitchOn());
        masterSwitch.setOnCheckedChangeListener((button, checked) ->
                worker.execute(() -> settings.setGroupMasterSwitchOn(checked)));

        renderRegion();
        regionPick.setOnClickListener(v -> openRegionPicker());
        view.findViewById(R.id.settings_region_clear).setOnClickListener(v -> clearRegion());

        worker.execute(() -> {
            RegionCatalog loaded = RegionCatalog.fromAssets(requireContext().getApplicationContext());
            HolidayCalendar calendar = HolidayCalendar.loadFromAssets(
                    requireContext().getApplicationContext());
            int year = java.time.LocalDate.now().getYear();
            String holidayText;
            if (calendar.isDegraded()) {
                holidayText = getString(R.string.settings_holiday_degraded);
            } else if (calendar.hasDataFor(year)) {
                holidayText = getString(R.string.settings_holiday_ok, calendar.holidayCount());
            } else {
                holidayText = getString(R.string.settings_holiday_missing, year);
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    catalog = loaded;
                    holidayStatus.setText(holidayText);
                    applyCatalogState();
                });
            }
        });
    }

    private void applyCatalogState() {
        boolean available = catalog != null && !catalog.isDegraded()
                && !catalog.provinces().isEmpty();
        regionPick.setEnabled(available);
        regionHint.setVisibility(available ? View.GONE : View.VISIBLE);
    }

    private void openRegionPicker() {
        if (catalog == null || catalog.isDegraded() || catalog.provinces().isEmpty()) {
            Snackbar.make(requireView(), R.string.settings_region_degraded,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        RegionPickerDialog.show(requireContext(), catalog, settings.getCity(), picked -> {
            settings.setCity(picked);
            renderRegion();
            Snackbar.make(requireView(), getString(R.string.settings_region_saved, picked),
                    Snackbar.LENGTH_SHORT).show();
            refreshForecast(picked);
        });
    }

    private void clearRegion() {
        settings.setCity("");
        renderRegion();
        Snackbar.make(requireView(), R.string.settings_city_cleared, Snackbar.LENGTH_SHORT).show();
    }

    private void renderRegion() {
        String region = settings.getCity();
        boolean hasRegion = !TextUtils.isEmpty(region);
        regionValue.setText(hasRegion ? region : getString(R.string.settings_region_none));
        regionValue.setTextColor(requireContext().getColor(
                hasRegion ? R.color.ink : R.color.muted));
        weatherStatus.setText(hasRegion
                ? getString(R.string.settings_weather_on, region)
                : getString(R.string.settings_weather_off));
    }

    /** Pulls the forecast right away so the first alarm already benefits from it. */
    private void refreshForecast(String region) {
        worker.execute(() -> {
            try {
                new WeatherClient(TongxiaoDb.get(requireContext()).weatherCacheDao())
                        .refresh(region);
            } catch (RuntimeException ignored) {
                // offline is fine: no cache means no advance, never a wrong advance
            }
        });
    }

    @Override
    public void onDestroyView() {
        worker.shutdown();
        super.onDestroyView();
    }
}
