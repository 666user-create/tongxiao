package com.course.tongxiao.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.course.tongxiao.R;
import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;

import java.util.List;

/** Step 3.1/3.3: alarm list tab (design.md §7 ①), hosted by MainActivity. */
public class AlarmListFragment extends Fragment {

    private AlarmListViewModel viewModel;
    private AlarmListAdapter adapter;
    private RecyclerView list;
    private View emptyState;
    private TextView subtitle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alarm_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        list = view.findViewById(R.id.alarm_list);
        emptyState = view.findViewById(R.id.empty_state);
        subtitle = view.findViewById(R.id.tomorrow_status);

        TongxiaoDb db = TongxiaoDb.get(requireContext());
        AlarmRepository repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(
                HolidayCalendar.loadFromAssets(requireContext()));
        AlarmScheduler scheduler = new AlarmScheduler(requireContext(), repository, resolver);

        viewModel = new ViewModelProvider(this, new Factory(repository, scheduler, resolver))
                .get(AlarmListViewModel.class);

        adapter = new AlarmListAdapter(
                (alarm, enabled) -> viewModel.setEnabled(alarm, enabled),
                this::openEditor,
                new com.course.tongxiao.engine.NextTriggerCalculator(resolver));
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        // Re-arming on every app start: AlarmManager registrations are lost when
        // the APK is reinstalled or the process is force-stopped, which looks like
        // "the alarm never rings" until the next boot event.
        new Thread(() -> {
            try {
                scheduler.rescheduleAll();
                refreshWeatherIfStale(db);
            } catch (RuntimeException ignored) {
                // never block the UI on this
            }
        }, "alarm-rearm").start();

        viewModel.getAlarms().observe(getViewLifecycleOwner(), this::render);
        viewModel.getTomorrowStatus().observe(getViewLifecycleOwner(), subtitle::setText);

        view.findViewById(R.id.add_alarm).setOnClickListener(v -> openEditor(null));
    }

    /**
     * Fetches tomorrow's forecast at most every 12 hours (design.md §5.3). Runs on
     * the caller's background thread; a failure just leaves the cached value
     * (stale cache = no advance, never a wrong advance).
     */
    private void refreshWeatherIfStale(TongxiaoDb db) {
        String city = new com.course.tongxiao.data.SettingsStore(requireContext()).getCity();
        if (city == null || city.trim().isEmpty()) {
            return; // weather link is off
        }
        String tomorrow = java.time.LocalDate.now().plusDays(1).toString();
        com.course.tongxiao.data.entity.WeatherCache cached = db.weatherCacheDao().get(city, tomorrow);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt < 12L * 60 * 60 * 1000) {
            return; // fresh enough
        }
        new com.course.tongxiao.engine.weather.WeatherClient(db.weatherCacheDao()).refresh(city);
    }

    private void openEditor(@Nullable Alarm alarm) {
        Intent edit = new Intent(requireContext(), EditAlarmActivity.class);
        if (alarm != null) {
            edit.putExtra(EditAlarmActivity.EXTRA_ALARM_ID, alarm.id);
        }
        startActivity(edit);
    }

    private void render(List<Alarm> alarms) {
        adapter.submitList(alarms);
        boolean empty = alarms == null || alarms.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshTomorrowStatus();
        }
    }

    /** Hand-rolled factory: no DI framework in this project by design. */
    private static final class Factory implements ViewModelProvider.Factory {
        private final AlarmRepository repository;
        private final AlarmScheduler scheduler;
        private final HolidayResolver resolver;

        Factory(AlarmRepository repository, AlarmScheduler scheduler, HolidayResolver resolver) {
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
