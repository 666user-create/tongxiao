package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.SettingsStore;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Step 3.3 G5: the settings tab renders and its edits reach SettingsStore. */
@RunWith(AndroidJUnit4.class)
public class SettingsTabTest {

    private static void openSettingsTab(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity ->
                ((BottomNavigationView) activity.findViewById(R.id.bottom_nav))
                        .setSelectedItemId(R.id.tab_settings));
    }

    private static <T extends View> T awaitView(ActivityScenario<MainActivity> scenario,
                                                int id, Class<T> type) throws Exception {
        for (int i = 0; i < 50; i++) {
            final View[] found = {null};
            scenario.onActivity(activity -> found[0] = activity.findViewById(id));
            if (found[0] != null) {
                return type.cast(found[0]);
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    public void settingsTab_showsHolidayStatusAndStoredValues() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        SettingsStore store = new SettingsStore(ctx);
        String originalCity = store.getCity();
        boolean originalMaster = store.isGroupMasterSwitchOn();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettingsTab(scenario);

            TextInputEditText city = awaitView(scenario, R.id.settings_city, TextInputEditText.class);
            assertNotNull("settings tab rendered", city);
            assertEquals("city prefilled from SettingsStore", originalCity, String.valueOf(city.getText()));

            MaterialSwitch master = awaitView(scenario, R.id.settings_group_master, MaterialSwitch.class);
            assertNotNull(master);
            assertEquals(originalMaster, master.isChecked());

            TextView holiday = awaitView(scenario, R.id.settings_holiday_status, TextView.class);
            assertNotNull(holiday);
            assertTrue("holiday status must explain the data state",
                    holiday.getText() != null && holiday.getText().length() > 0);
        }
    }

    @Test
    public void editingCity_persistsToSettingsStore() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        SettingsStore store = new SettingsStore(ctx);
        String originalCity = store.getCity();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettingsTab(scenario);
            TextInputEditText city = awaitView(scenario, R.id.settings_city, TextInputEditText.class);
            assertNotNull(city);

            scenario.onActivity(activity -> city.setText("杭州"));
            Thread.sleep(600); // TextWatcher → SettingsStore.apply()

            assertEquals("杭州", store.getCity());
        } finally {
            store.setCity(originalCity);
        }
    }

    @Test
    public void alarmsTabIsDefault() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(800);
            View list = awaitView(scenario, R.id.alarm_list, View.class);
            View empty = awaitView(scenario, R.id.empty_state, View.class);
            assertNotNull("alarm tab content inflated by default", list);
            assertNotNull("empty state exists", empty);
            // With no alarms the list hides and the empty state shows; either way
            // the alarms tab is what the user sees (not the settings form).
            boolean alarmsTabVisible = list.getVisibility() == View.VISIBLE
                    || empty.getVisibility() == View.VISIBLE;
            assertTrue("alarms tab must be the default tab", alarmsTabVisible);
            View settingsForm = awaitView(scenario, R.id.settings_city, View.class);
            assertTrue("settings tab is not the default",
                    settingsForm == null || settingsForm.getVisibility() == View.GONE);
        }
    }
}
