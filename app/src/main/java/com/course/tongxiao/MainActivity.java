package com.course.tongxiao;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.ui.AlarmListAdapter;
import com.course.tongxiao.ui.AlarmListViewModel;

import java.util.List;

/**
 * Step 3.1: alarm list screen (design.md §7 ①).
 *
 * <p>Shows the alarms, a "tomorrow is a workday / holiday" subtitle (the holiday
 * engine made visible), an empty state, and lets the switch enable/disable an
 * alarm. The edit screen arrives in Step 3.2.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 100;

    private AlarmListViewModel viewModel;
    private AlarmListAdapter adapter;
    private RecyclerView list;
    private View emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.alarm_list);
        emptyState = findViewById(R.id.empty_state);

        TongxiaoDb db = TongxiaoDb.get(this);
        AlarmRepository repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(HolidayCalendar.loadFromAssets(this));
        AlarmScheduler scheduler = new AlarmScheduler(this, repository, resolver);

        viewModel = new ViewModelProvider(this,
                new AlarmListViewModelFactory(repository, scheduler, resolver))
                .get(AlarmListViewModel.class);

        adapter = new AlarmListAdapter(
                (alarm, enabled) -> viewModel.setEnabled(alarm, enabled),
                alarm -> {
                    Intent edit = new Intent(this, com.course.tongxiao.ui.EditAlarmActivity.class);
                    edit.putExtra(com.course.tongxiao.ui.EditAlarmActivity.EXTRA_ALARM_ID, alarm.id);
                    startActivity(edit);
                });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.add_alarm).setOnClickListener(v ->
                startActivity(new Intent(this, com.course.tongxiao.ui.EditAlarmActivity.class)));

        viewModel.getAlarms().observe(this, this::render);
        viewModel.getTomorrowStatus().observe(this,
                text -> ((android.widget.TextView) findViewById(R.id.tomorrow_status)).setText(text));

        ensureNotificationPermission();
    }

    private void render(List<com.course.tongxiao.data.entity.Alarm> alarms) {
        adapter.submitList(alarms);
        boolean empty = alarms == null || alarms.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshTomorrowStatus();
        }
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

    /** Simple hand-rolled factory: no DI framework in this project by design. */
    private static final class AlarmListViewModelFactory
            implements ViewModelProvider.Factory {
        private final AlarmRepository repository;
        private final AlarmScheduler scheduler;
        private final HolidayResolver resolver;

        AlarmListViewModelFactory(AlarmRepository repository, AlarmScheduler scheduler,
                                  HolidayResolver resolver) {
            this.repository = repository;
            this.scheduler = scheduler;
            this.resolver = resolver;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends androidx.lifecycle.ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(AlarmListViewModel.class)) {
                return (T) new AlarmListViewModel(repository, scheduler, resolver);
            }
            throw new IllegalArgumentException("unknown ViewModel: " + modelClass.getName());
        }
    }
}
