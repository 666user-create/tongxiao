package com.course.tongxiao;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.course.tongxiao.ui.AlarmListFragment;
import com.course.tongxiao.ui.SettingsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Step 3.3: tab host (design.md §7). Tabs: alarms / settings; the dorm tab joins
 * with Step 5.5 when the peer-link UI exists.
 *
 * <p>Fragments are swapped manually (FragmentContainerView + show/hide) — the
 * Navigation component is not a dependency of this project by design.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 100;

    private static final String TAG_ALARMS = "tab_alarms";
    private static final String TAG_SETTINGS = "tab_settings";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        // Edge-to-edge (targetSdk 36): the container needs the status bar inset and
        // the bottom bar needs the navigation-bar inset, otherwise the toolbar is
        // drawn underneath the system UI.
        com.course.tongxiao.ui.UiInsets.padSystemBars(findViewById(R.id.tab_container));
        com.course.tongxiao.ui.UiInsets.padBottomBar(nav);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.tab_settings) {
                showSettings();
            } else {
                showAlarms();
            }
            return true;
        });

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.tab_alarms);
            showAlarms();
        } else {
            // Fragments were restored by the FragmentManager.
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.tab_container);
            if (current instanceof SettingsFragment) {
                nav.setSelectedItemId(R.id.tab_settings);
            } else {
                nav.setSelectedItemId(R.id.tab_alarms);
            }
        }

        ensureNotificationPermission();
    }

    private void showAlarms() {
        swap(TAG_ALARMS, AlarmListFragment::new);
    }

    private void showSettings() {
        swap(TAG_SETTINGS, SettingsFragment::new);
    }

    /**
     * Adds the target fragment once, then hides every other tab so state (scroll
     * position, switch state) survives tab switching.
     */
    private void swap(String tag, java.util.function.Supplier<Fragment> factory) {
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
        for (Fragment existing : fm.getFragments()) {
            if (existing.getId() == R.id.tab_container) {
                tx.hide(existing);
            }
        }
        Fragment target = fm.findFragmentByTag(tag);
        if (target == null) {
            target = factory.get();
            tx.add(R.id.tab_container, target, tag);
        } else {
            tx.show(target);
        }
        tx.commit();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }

    @NonNull
    @Override
    public String toString() {
        return "MainActivity{tabs=alarms,settings}";
    }
}
