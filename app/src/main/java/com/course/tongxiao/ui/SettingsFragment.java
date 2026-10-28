package com.course.tongxiao.ui;

import android.app.Activity;
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

    /** Recreatable: a config change destroys the view but keeps the fragment. */
    private ExecutorService worker = Executors.newSingleThreadExecutor();

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
        // Capture everything the background work needs BEFORE leaving the UI thread:
        // a detached fragment throws on requireContext(), and getString() off the
        // main thread risks a race with view destruction. See AlarmsFragment's
        // pattern for the same reason.
        final android.content.Context appContext = requireContext().getApplicationContext();
        final String textDegraded = getString(R.string.settings_holiday_degraded);
        final String textOkFormat = getString(R.string.settings_holiday_ok);
        final String textMissingFormat = getString(R.string.settings_holiday_missing);
        final int year = java.time.LocalDate.now().getYear();

        settings = new SettingsStore(appContext);
        holidayStatus = view.findViewById(R.id.settings_holiday_status);
        weatherStatus = view.findViewById(R.id.weather_status);
        regionValue = view.findViewById(R.id.settings_region_value);
        regionHint = view.findViewById(R.id.settings_region_hint);
        regionPick = view.findViewById(R.id.settings_region_pick);
        masterSwitch = view.findViewById(R.id.settings_group_master);

        masterSwitch.setChecked(settings.isGroupMasterSwitchOn());
        masterSwitch.setOnCheckedChangeListener((button, checked) -> submit(
                () -> settings.setGroupMasterSwitchOn(checked)));

        renderRegion();
        // Until the catalog is parsed the picker cannot open; say "loading", not
        // "unavailable" (the old copy accused the data of being broken).
        regionPick.setEnabled(false);
        regionHint.setVisibility(View.VISIBLE);
        regionHint.setText(R.string.settings_region_loading);
        regionPick.setOnClickListener(v -> openRegionPicker());
        view.findViewById(R.id.settings_region_clear).setOnClickListener(v -> clearRegion());

        submit(() -> {
            RegionCatalog loaded = RegionCatalog.fromAssets(appContext);
            HolidayCalendar calendar = HolidayCalendar.loadFromAssets(appContext);
            String holidayText;
            if (calendar.isDegraded()) {
                holidayText = textDegraded;
            } else if (calendar.hasDataFor(year)) {
                holidayText = String.format(textOkFormat, calendar.holidayCount());
            } else {
                holidayText = String.format(textMissingFormat, year);
            }
            final String finalText = holidayText;
            runOnUiIfAttached(() -> {
                catalog = loaded;
                holidayStatus.setText(finalText);
                applyCatalogState();
            });
        });
    }

    /**
     * Runs {@code task} on the worker, recreating the executor when a previous view
     * destroyed it (a fragment instance outlives its view across config changes).
     */
    private void submit(@NonNull Runnable task) {
        if (worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor();
        }
        worker.execute(task);
    }

    /** Posts to the activity only when the fragment still has a view attached. */
    private void runOnUiIfAttached(@NonNull Runnable action) {
        if (!isAdded()) {
            return;
        }
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (isAdded() && getView() != null) {
                    action.run();
                }
            });
        }
    }

    private void applyCatalogState() {
        boolean available = catalog != null && !catalog.isDegraded()
                && !catalog.provinces().isEmpty();
        regionPick.setEnabled(available);
        regionHint.setVisibility(available ? View.GONE : View.VISIBLE);
        if (!available) {
            regionHint.setText(R.string.settings_region_degraded);
        }
    }

    private void openRegionPicker() {
        if (catalog == null || catalog.isDegraded() || catalog.provinces().isEmpty()) {
            Snackbar.make(requireView(), R.string.settings_region_degraded,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        // A value saved by an older build (free text "杭州", no 市 suffix) cannot be
        // mapped onto the wheels. Preselecting the first province would silently
        // rewrite it to 北京 on the next 确定, so start from scratch and say why.
        String current = settings.getCity();
        String preselect = current;
        if (!TextUtils.isEmpty(current)) {
            String first = current.trim().split("\\s+")[0];
            if (!catalog.provinces().contains(first)) {
                preselect = null;
                Snackbar.make(requireView(), R.string.settings_region_reregister,
                        Snackbar.LENGTH_LONG).show();
            }
        }
        RegionPickerDialog.show(requireContext(), catalog, preselect, picked -> {
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
        final android.content.Context appContext = requireContext().getApplicationContext();
        submit(() -> {
            try {
                new WeatherClient(TongxiaoDb.get(appContext).weatherCacheDao()).refresh(region);
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
