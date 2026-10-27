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
import com.course.tongxiao.engine.HolidayCalendar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 3.3: settings tab (design.md §7 ⑤).
 *
 * <p>City editing is an explicit action with visible confirmation: typing alone
 * does not save (the previous auto-save gave no sense of completion), and the
 * status line always states whether the weather link is on.
 */
public class SettingsFragment extends Fragment {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SettingsStore settings;
    private TextView holidayStatus;
    private TextView weatherStatus;
    private TextInputEditText cityInput;
    private MaterialSwitch masterSwitch;
    private MaterialButtonToggleGroup cityChips;

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
        cityInput = view.findViewById(R.id.settings_city);
        masterSwitch = view.findViewById(R.id.settings_group_master);
        cityChips = view.findViewById(R.id.settings_city_chips);

        cityInput.setText(settings.getCity());
        masterSwitch.setChecked(settings.isGroupMasterSwitchOn());
        renderWeatherStatus();

        view.findViewById(R.id.settings_city_save).setOnClickListener(v -> saveCity(cityInput.getText() == null
                ? "" : cityInput.getText().toString()));
        view.findViewById(R.id.settings_city_clear).setOnClickListener(v -> {
            cityInput.setText("");
            settings.setCity("");
            renderWeatherStatus();
            Snackbar.make(view, R.string.settings_city_cleared, Snackbar.LENGTH_SHORT).show();
        });

        cityChips.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            String city = null;
            if (checkedId == R.id.chip_city_beijing) {
                city = getString(R.string.city_beijing);
            } else if (checkedId == R.id.chip_city_shanghai) {
                city = getString(R.string.city_shanghai);
            } else if (checkedId == R.id.chip_city_hangzhou) {
                city = getString(R.string.city_hangzhou);
            } else if (checkedId == R.id.chip_city_shenzhen) {
                city = getString(R.string.city_shenzhen);
            }
            if (city != null) {
                cityInput.setText(city);
                saveCity(city);
            }
        });

        masterSwitch.setOnCheckedChangeListener((button, checked) ->
                worker.execute(() -> settings.setGroupMasterSwitchOn(checked)));

        renderHolidayStatus();
    }

    private void saveCity(String raw) {
        String city = raw == null ? "" : raw.trim();
        if (TextUtils.isEmpty(city)) {
            Snackbar.make(requireView(), R.string.settings_city_invalid, Snackbar.LENGTH_SHORT).show();
            return;
        }
        settings.setCity(city);
        renderWeatherStatus();
        Snackbar.make(requireView(), getString(R.string.settings_city_saved, city),
                Snackbar.LENGTH_SHORT).show();
    }

    private void renderWeatherStatus() {
        String city = settings.getCity();
        weatherStatus.setText(TextUtils.isEmpty(city)
                ? getString(R.string.settings_weather_off)
                : getString(R.string.settings_weather_on, city));
    }

    private void renderHolidayStatus() {
        worker.execute(() -> {
            HolidayCalendar calendar = HolidayCalendar.loadFromAssets(
                    requireContext().getApplicationContext());
            int year = java.time.LocalDate.now().getYear();
            String text;
            if (calendar.isDegraded()) {
                text = getString(R.string.settings_holiday_degraded);
            } else if (calendar.hasDataFor(year)) {
                text = getString(R.string.settings_holiday_ok, calendar.holidayCount());
            } else {
                text = getString(R.string.settings_holiday_missing, year);
            }
            final String finalText = text;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> holidayStatus.setText(finalText));
            }
        });
    }

    @Override
    public void onDestroyView() {
        worker.shutdown();
        super.onDestroyView();
    }
}
