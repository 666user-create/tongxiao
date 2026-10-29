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
    private MaterialButton regionClear;
    private MaterialSwitch masterSwitch;
    private TextView customQuestionsSummary;

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
        regionClear = view.findViewById(R.id.settings_region_clear);
        masterSwitch = view.findViewById(R.id.settings_group_master);
        customQuestionsSummary = view.findViewById(R.id.settings_custom_questions_summary);
        TextView version = view.findViewById(R.id.settings_version);
        if (version != null) {
            // Lets the user (and the course reviewer) confirm which build is installed.
            version.setText(getString(R.string.settings_version,
                    com.course.tongxiao.BuildConfig.VERSION_NAME,
                    com.course.tongxiao.BuildConfig.VERSION_CODE));
        }
        view.findViewById(R.id.settings_custom_questions).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(),
                        CustomQuestionActivity.class)));

        masterSwitch.setChecked(settings.isGroupMasterSwitchOn());
        masterSwitch.setOnCheckedChangeListener((button, checked) -> submit(
                () -> settings.setGroupMasterSwitchOn(checked)));

        renderRegion();
        renderCustomQuestionsSummary();
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

    /**
     * Settings has no CoordinatorLayout, so a Snackbar docks over the bottom navigation
     * and blocks it for ~2s. A Toast floats above it without stealing touches.
     */
    private void toast(int messageRes) {
        android.widget.Toast.makeText(requireContext(), messageRes,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toastText(String message) {
        android.widget.Toast.makeText(requireContext(), message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Shows how many of the user's own questions the challenge will draw from. */
    private void renderCustomQuestionsSummary() {
        if (customQuestionsSummary == null || !isAdded()) {
            return;
        }
        int count = new com.course.tongxiao.engine.challenge.CustomQuestionStore(requireContext())
                .size();
        customQuestionsSummary.setText(count == 0
                ? getString(R.string.settings_custom_questions_none)
                : getString(R.string.settings_custom_questions_summary, count));
    }

    @Override
    public void onResume() {
        super.onResume();
        renderCustomQuestionsSummary(); // the editor may have added/removed rows
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
            toast(R.string.settings_region_degraded);
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
                toast(R.string.settings_region_reregister);
            }
        }
        RegionPickerDialog.show(requireContext(), catalog, preselect, picked -> {
            settings.setCity(picked);
            renderRegion();
            toastText(getString(R.string.settings_region_saved, picked));
            refreshForecast(picked);
        });
    }

    private void clearRegion() {
        settings.setCity("");
        renderRegion();
        toast(R.string.settings_city_cleared);
    }

    private void renderRegion() {
        String region = settings.getCity();
        boolean hasRegion = !TextUtils.isEmpty(region);
        regionValue.setText(hasRegion ? region : getString(R.string.settings_region_none));
        regionValue.setTextColor(requireContext().getColor(
                hasRegion ? R.color.ink : R.color.muted));
        // "清除" next to 未选择地区 was a no-op that looked like a broken control.
        if (regionClear != null) {
            regionClear.setVisibility(hasRegion ? View.VISIBLE : View.GONE);
        }
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
