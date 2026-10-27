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

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Step 3.3 + region picker: the settings tab renders stored values and the region
 * selection is an explicit, confirmed action.
 *
 * <p>The three-wheel dialog itself is covered by
 * {@code RegionPickerTest} (it needs the catalog); here we assert the screen
 * contract: stored region is spelled out, clearing works, holiday state is shown.
 */
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
    public void settingsTab_showsStoredRegionAndHolidayState() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        SettingsStore store = new SettingsStore(ctx);
        String originalCity = store.getCity();
        boolean originalMaster = store.isGroupMasterSwitchOn();
        store.setCity("浙江省 杭州市 西湖区");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettingsTab(scenario);

            TextView region = awaitView(scenario, R.id.settings_region_value, TextView.class);
            assertNotNull("settings tab rendered", region);
            assertNotNull(region.getText());
            assertTrue("stored region path must be shown verbatim",
                    region.getText().toString().contains("西湖区"));

            MaterialSwitch master = awaitView(scenario, R.id.settings_group_master,
                    MaterialSwitch.class);
            assertNotNull(master);
            assertEquals(originalMaster, master.isChecked());

            TextView holiday = awaitView(scenario, R.id.settings_holiday_status, TextView.class);
            assertNotNull(holiday);
            assertTrue("holiday status must explain the data state",
                    holiday.getText() != null && holiday.getText().length() > 0);

            View pick = awaitView(scenario, R.id.settings_region_pick, View.class);
            assertNotNull("region picker entry point exists", pick);
        } finally {
            store.setCity(originalCity);
        }
    }

    @Test
    public void clearingRegion_turnsWeatherLinkOff() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        SettingsStore store = new SettingsStore(ctx);
        String originalCity = store.getCity();
        store.setCity("北京市 朝阳区");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettingsTab(scenario);
            TextView region = awaitView(scenario, R.id.settings_region_value, TextView.class);
            assertNotNull(region);

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.settings_region_clear).performClick());
            Thread.sleep(500);

            assertEquals("clearing must persist an empty region", "", store.getCity());
            assertTrue("empty state is spelled out",
                    region.getText().toString().contains("未选择"));
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
            View settingsForm = awaitView(scenario, R.id.settings_region_pick, View.class);
            assertTrue("settings tab is not the default",
                    settingsForm == null || settingsForm.getVisibility() == View.GONE);
        }
    }
}
