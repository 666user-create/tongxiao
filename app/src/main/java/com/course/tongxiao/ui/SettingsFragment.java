package com.course.tongxiao.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 3.3: settings tab (design.md §7 ⑤).
 *
 * <p>What lives here now: holiday-data status (the degraded banner made visible),
 * the weather city (empty = feature off), and the dorm-permission master switch
 * (a protocol-layer gate, design.md §3.2). Import-from-URL arrives with the
 * weather step (6.2); the dorm tab arrives with Step 5.5.
 */
public class SettingsFragment extends Fragment {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SettingsStore settings;
    private TextView holidayStatus;
    private TextInputEditText cityInput;
    private MaterialSwitch masterSwitch;

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
        cityInput = view.findViewById(R.id.settings_city);
        masterSwitch = view.findViewById(R.id.settings_group_master);

        cityInput.setText(settings.getCity());
        masterSwitch.setChecked(settings.isGroupMasterSwitchOn());

        cityInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                settings.setCity(s == null ? "" : s.toString());
            }
        });

        masterSwitch.setOnCheckedChangeListener((button, checked) ->
                worker.execute(() -> settings.setGroupMasterSwitchOn(checked)));

        renderHolidayStatus();
    }

    private void renderHolidayStatus() {
        worker.execute(() -> {
            HolidayCalendar calendar = HolidayCalendar.loadFromAssets(requireContext().getApplicationContext());
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
