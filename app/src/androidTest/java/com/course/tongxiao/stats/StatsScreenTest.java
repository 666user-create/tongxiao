package com.course.tongxiao.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.AlarmEvent;
import com.course.tongxiao.ui.stats.SleepTrendView;
import com.course.tongxiao.ui.stats.StatsAggregator;
import com.course.tongxiao.ui.stats.StatsFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step 6.3 G5: the stats screen really draws and really renders the log.
 *
 * <p>Three layers, cheapest first: the layout inflates (catches XML/class a
 * write-up), {@link SleepTrendView} paints a green polyline only when there is
 * data (catches "draws nothing" and "draws garbage instead of the empty hint"),
 * and {@link StatsFragment} shows the aggregated numbers read from Room.
 *
 * <p>Uses the app-wide DB because that is what the fragment reads; every row it
 * creates is removed afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class StatsScreenTest {

    private static final int SNOOZE_EVENTS = 9;

    private Context ctx;
    private TongxiaoDb db;
    private String label;
    private int alarmId = -1;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = TongxiaoDb.get(ctx);
    }

    @After
    public void tearDown() {
        if (alarmId > 0) {
            db.alarmEventDao().deleteByAlarm(alarmId);
            db.alarmDao().deleteById(alarmId);
            alarmId = -1;
        }
    }

    private long millisToday(int hour, int minute) {
        return LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** One alarm with a wake-up, a roommate mute and a pile of snoozes. */
    private void seedLog() {
        label = "统计测试-" + System.nanoTime();
        Alarm alarm = new Alarm();
        alarm.label = label;
        alarm.hour = 6;
        alarm.minute = 40;
        alarm.repeatMode = Alarm.RepeatMode.DAILY;
        alarm.enabled = false; // not armed: this test is about statistics only
        alarmId = (int) db.alarmDao().insert(alarm);

        List<AlarmEvent> events = new ArrayList<>();
        events.add(event(AlarmEvent.Type.STOPPED, "{\"source\":\"user\"}"));
        // Remote mute: counted by the "被舍友静音" card.
        events.add(event(AlarmEvent.Type.STOPPED, "{\"source\":\"remote:device-1\"}"));
        for (int i = 0; i < SNOOZE_EVENTS; i++) {
            events.add(event(AlarmEvent.Type.SNOOZE, null));
        }
        for (AlarmEvent event : events) {
            db.alarmEventDao().insert(event);
        }
    }

    private AlarmEvent event(AlarmEvent.Type type, String meta) {
        AlarmEvent event = new AlarmEvent();
        event.alarmId = alarmId;
        event.type = type;
        event.fireAt = millisToday(6, 40);
        event.stopAt = millisToday(6, 41);
        event.meta = meta;
        return event;
    }

    private static Bitmap render(View view, int width, int height) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int countPixels(Bitmap bitmap, int color) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    public void layout_inflatesTheChartAndAllThreeCards() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final View[] root = new View[1];
            // Inflate with the Activity (theme) context: the app context has no
            // AppCompat theme, and MaterialToolbar refuses to inflate without one.
            scenario.onActivity(activity -> root[0] = activity.getLayoutInflater()
                    .inflate(R.layout.fragment_stats, null, false));

            assertTrue("the chart is our custom view",
                    root[0].findViewById(R.id.stats_trend) instanceof SleepTrendView);
            assertNotNull(root[0].findViewById(R.id.stats_trend_caption));
            assertNotNull(root[0].findViewById(R.id.stats_remote_count));
            assertNotNull(root[0].findViewById(R.id.stats_snooze_row_1));
            assertNotNull(root[0].findViewById(R.id.stats_snooze_row_2));
            assertNotNull(root[0].findViewById(R.id.stats_snooze_row_3));
        }
    }

    @Test
    public void emptyTrend_paintsTheHintAndNoPolyline() {
        int green = ContextCompat.getColor(ctx, R.color.green_primary);
        SleepTrendView view = new SleepTrendView(ctx);
        view.setTrend(Collections.emptyList(), LocalDate.now());

        Bitmap bitmap = render(view, 720, 400);

        assertEquals("no wake-up data must not draw a data line", 0, countPixels(bitmap, green));
        assertTrue("empty trend is announced to accessibility services",
                view.getContentDescription().toString().contains("0"));
    }

    @Test
    public void populatedTrend_paintsThePolyline() {
        int green = ContextCompat.getColor(ctx, R.color.green_primary);
        LocalDate today = LocalDate.now();
        List<StatsAggregator.WakePoint> points = new ArrayList<>();
        points.add(new StatsAggregator.WakePoint(today.minusDays(2), 6 * 60 + 40));
        points.add(new StatsAggregator.WakePoint(today.minusDays(1), 7 * 60 + 5));
        points.add(new StatsAggregator.WakePoint(today, 6 * 60 + 55));

        SleepTrendView view = new SleepTrendView(ctx);
        view.setTrend(points, today);
        Bitmap bitmap = render(view, 720, 400);

        assertTrue("three points must produce visible green strokes",
                countPixels(bitmap, green) > 0);
        assertTrue("point count is exposed to accessibility services",
                view.getContentDescription().toString().contains("3"));
    }

    @Test
    public void statsFragment_rendersTrendSnoozeRankingAndMuteCounter() throws Exception {
        seedLog();

        final StatsFragment[] fragment = new StatsFragment[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                fragment[0] = new StatsFragment();
                activity.getSupportFragmentManager().beginTransaction()
                        .add(R.id.tab_container, fragment[0], "stats_test")
                        .commitNow();
            });

            String caption = "";
            String firstSnoozeRow = "";
            String remoteCount = "";
            String diagnostics = "";
            for (int attempt = 0; attempt < 60; attempt++) {
                final String[] read = new String[4];
                scenario.onActivity(activity -> {
                    View view = fragment[0].getView();
                    read[3] = "added=" + fragment[0].isAdded() + " hidden=" + fragment[0].isHidden()
                            + " state=" + fragment[0].getLifecycle().getCurrentState()
                            + " view=" + (view == null ? "null" : view.getClass().getSimpleName());
                    if (view == null) {
                        return;
                    }
                    read[0] = text(view, R.id.stats_trend_caption);
                    read[1] = text(view, R.id.stats_snooze_row_1);
                    read[2] = text(view, R.id.stats_remote_count);
                });
                caption = read[0] == null ? "" : read[0];
                firstSnoozeRow = read[1] == null ? "" : read[1];
                remoteCount = read[2] == null ? "" : read[2];
                diagnostics = read[3] == null ? "" : read[3];
                if (caption.startsWith("最近 14 天记录了")
                        && firstSnoozeRow.contains(label)
                        && remoteCount.endsWith("次")) {
                    break;
                }
                Thread.sleep(100);
            }

            assertTrue("trend caption rendered: '" + caption + "' (" + diagnostics + ")",
                    caption.startsWith("最近 14 天记录了"));
            assertTrue("at least one recorded day: '" + caption + "'",
                    daysRecorded(caption) >= 1);
            assertTrue("snooze ranking names the alarm: '" + firstSnoozeRow + "'",
                    firstSnoozeRow.contains(label));
            assertTrue("snooze ranking shows the count: '" + firstSnoozeRow + "'",
                    firstSnoozeRow.contains(String.valueOf(SNOOZE_EVENTS)));
            assertTrue("remote mute counter rendered: '" + remoteCount + "'",
                    remoteCount.endsWith("次") && remoteMutes(remoteCount) >= 1);
        }
    }

    @Test
    public void unnamedAlarm_isNotReportedAsDeleted() throws Exception {
        // Regression: an alarm with an empty label was rendered as 已删除的闹钟 in the
        // snooze ranking even though the row still existed.
        Alarm unnamed = new Alarm();
        unnamed.label = "";
        unnamed.hour = 6;
        unnamed.minute = 50;
        unnamed.repeatMode = Alarm.RepeatMode.DAILY;
        unnamed.enabled = false;
        int unnamedId = (int) db.alarmDao().insert(unnamed);
        try {
            for (int i = 0; i < 3; i++) {
                AlarmEvent event = new AlarmEvent();
                event.alarmId = unnamedId;
                event.type = AlarmEvent.Type.SNOOZE;
                event.fireAt = millisToday(6, 50);
                event.stopAt = millisToday(6, 51);
                db.alarmEventDao().insert(event);
            }

            final StatsFragment[] fragment = new StatsFragment[1];
            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    fragment[0] = new StatsFragment();
                    activity.getSupportFragmentManager().beginTransaction()
                            .add(R.id.tab_container, fragment[0], "stats_unnamed_test")
                            .commitNow();
                });

                String row = "";
                for (int attempt = 0; attempt < 60; attempt++) {
                    final String[] read = new String[1];
                    scenario.onActivity(activity -> {
                        View view = fragment[0].getView();
                        if (view != null) {
                            read[0] = text(view, R.id.stats_snooze_row_1);
                        }
                    });
                    row = read[0] == null ? "" : read[0];
                    if (row.contains("贪睡 3 次")) {
                        break;
                    }
                    Thread.sleep(100);
                }

                assertTrue("snooze row rendered: '" + row + "'", row.contains("贪睡 3 次"));
                assertFalse("an existing unnamed alarm must not be called deleted: '" + row + "'",
                        row.contains("已删除"));
                assertTrue("it shows the same default label as the list screen: '" + row + "'",
                        row.contains("闹钟"));
            }
        } finally {
            db.alarmEventDao().deleteByAlarm(unnamedId);
            db.alarmDao().deleteById(unnamedId);
        }
    }

    private static String text(View root, int id) {
        View view = root.findViewById(id);
        if (!(view instanceof TextView)) {
            return null;
        }
        return ((TextView) view).getText().toString();
    }

    /** "最近 14 天记录了 9 天" → 9. */
    private static int daysRecorded(String caption) {
        int days = -1;
        for (String part : caption.split("\\s+")) {
            try {
                days = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                // not the number token
            }
        }
        return days;
    }

    /** "4 次" → 4. */
    private static int remoteMutes(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Integer.parseInt(digits);
    }
}
