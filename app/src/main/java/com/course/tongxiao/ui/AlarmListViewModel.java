package com.course.tongxiao.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.course.tongxiao.alarm.AlarmScheduler;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayResolver;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 3.1: drives the alarm list (design.md §7 ①).
 *
 * <p>Reads are LiveData (Room dispatches them); writes are blocking repository
 * calls pushed onto the ViewModel's own executor, so the list screen never
 * touches the database on the main thread.
 */
public class AlarmListViewModel extends ViewModel {

    private final AlarmRepository repository;
    private final AlarmScheduler scheduler;
    private final HolidayResolver resolver;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final LiveData<List<Alarm>> alarms;

    /** Recomputed per render; kept in LiveData so rotation does not lose it. */
    private final MutableLiveData<String> tomorrowStatus = new MutableLiveData<>();

    public AlarmListViewModel(AlarmRepository repository, AlarmScheduler scheduler,
                              HolidayResolver resolver) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.resolver = resolver;
        this.alarms = repository.observeAll();
        refreshTomorrowStatus();
    }

    public LiveData<List<Alarm>> getAlarms() {
        return alarms;
    }

    public LiveData<String> getTomorrowStatus() {
        return tomorrowStatus;
    }

    /**
     * Toggle from the list switch: DB first (so the UI reflects intent even if
     * scheduling is refused), then register/cancel with AlarmManager.
     *
     * <p>Deliberately does NOT mutate {@code alarm.enabled} in place: that object is
     * the row currently held by the adapter's ListAdapter, so mutating it makes
     * DiffUtil.areContentsTheSame() report "no change" for the fresh Room emission
     * and the row is never rebound — the derived "下次：…" line then kept advertising
     * a wake-up for an alarm the user had just switched off.
     *
     * <p>Because of that, the object handed to the scheduler must be a <b>fresh row</b>
     * from the database: {@code Alarmscheduler.schedule()} refuses to register a row
     * whose {@code enabled} is false, and the adapter's instance still carries the old
     * value. Reusing it made "switch an alarm back on" silently do nothing.
     */
    public void setEnabled(Alarm alarm, boolean enabled) {
        worker.execute(() -> {
            repository.setEnabled(alarm.id, enabled);
            try {
                if (enabled) {
                    Alarm fresh = repository.getById(alarm.id);
                    if (fresh != null) {
                        scheduler.schedule(fresh);
                    }
                } else {
                    scheduler.cancel(alarm);
                }
            } catch (RuntimeException e) {
                // Scheduling failure must not corrupt the list state.
            }
        });
    }

    /**
     * "明天是工作日 / 明天是法定假 / 明天是周末（节假日数据未更新）".
     * A year without a notice is reported explicitly instead of silently lying.
     */
    public void refreshTomorrowStatus() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        String text;
        if (!resolver.hasDataFor(tomorrow)) {
            text = "节假日数据未更新（" + tomorrow.getYear() + "）";
        } else if (resolver.isStatutoryHoliday(tomorrow)) {
            text = "明天是法定假，智能工作日闹钟不响";
        } else if (resolver.isMakeupWorkday(tomorrow)) {
            text = "明天是调休补班日，工作日照常响";
        } else if (resolver.isSmartWorkday(tomorrow)) {
            text = "明天是工作日";
        } else {
            text = "明天是周末";
        }
        tomorrowStatus.postValue(text);
    }

    @Override
    protected void onCleared() {
        worker.shutdown();
    }
}
