package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.ui.stats.StatsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tab-host regression: exactly one fragment per tab may be attached.
 *
 * <p>Both bugs this guards against are layout bugs the user sees:
 * <ul>
 *   <li>{@code onCreate} selected the default tab (which fires the listener and adds
 *       the list) and then called {@code showAlarms()} again. Because
 *       {@code FragmentTransaction.commit()} is asynchronous, the second call did
 *       not see the first fragment and added a second one: two alarm lists were
 *       drawn on top of each other, and scrolling one produced the "overlapping
 *       rows" mess.</li>
 *   <li>The same race hit fast tab switching: queued transactions each hid only the
 *       fragments that were already executed, so the previous tab stayed visible
 *       underneath the new one.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityTabsTest {

    private static int countViewsWithId(View root, int id) {
        if (root == null) {
            return 0;
        }
        int count = root.getId() == id ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countViewsWithId(group.getChildAt(i), id);
            }
        }
        return count;
    }

    private static int visibleFragments(ActivityScenario<MainActivity> scenario,
                                        Class<? extends Fragment> type) {
        final int[] count = {0};
        scenario.onActivity(activity -> {
            int visible = 0;
            for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
                if (type.isInstance(fragment) && fragment.isVisible()) {
                    visible++;
                }
            }
            count[0] = visible;
        });
        return count[0];
    }

    @Test
    public void launch_attachesExactlyOneAlarmListFragment() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(800);
            assertEquals("the alarm list must be added exactly once on a cold start",
                    1, visibleFragments(scenario, AlarmListFragment.class));

            final int[] inflatedLists = {0};
            scenario.onActivity(activity -> inflatedLists[0] = countViewsWithId(
                    activity.getWindow().getDecorView(), R.id.alarm_list));
            assertEquals("only one alarm list view may exist in the hierarchy",
                    1, inflatedLists[0]);
        }
    }

    @Test
    public void fastTabSwitching_neverStacksTwoTabs() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(500);

            // Three swaps back-to-back on the UI thread: this is exactly the timing
            // that used to leave the old tab visible under the new one.
            scenario.onActivity(activity -> {
                BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
                nav.setSelectedItemId(R.id.tab_stats);
                nav.setSelectedItemId(R.id.tab_settings);
                nav.setSelectedItemId(R.id.tab_alarms);
            });
            Thread.sleep(600);

            assertEquals("only the alarm tab may be visible after switching back",
                    1, visibleFragments(scenario, AlarmListFragment.class));
            assertEquals("the stats tab must be hidden again",
                    0, visibleFragments(scenario, StatsFragment.class));

            final int[] inflatedLists = {0};
            scenario.onActivity(activity -> inflatedLists[0] = countViewsWithId(
                    activity.getWindow().getDecorView(), R.id.alarm_list));
            assertEquals("tab switching must not duplicate the alarm list",
                    1, inflatedLists[0]);

            final View[] list = {null};
            scenario.onActivity(activity -> list[0] = activity.findViewById(R.id.alarm_list));
            assertNotNull("the alarm list is still on screen", list[0]);
            assertTrue("the visible list is attached to a window",
                    list[0].isAttachedToWindow());
        }
    }
}
