package com.course.tongxiao.ui.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.course.tongxiao.R;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 6.3 wake-up trend chart, hand-drawn (design.md §7 ⑥, no chart library).
 *
 * <p>X = the last {@link StatsAggregator#TREND_DAYS} local days (fixed slots, so a
 * missing day is a visible gap rather than a shifted line); Y = the first stop of
 * that day as minutes since midnight (00:00 at the top of the axis, 24:00 at the
 * bottom).
 *
 * <p>Deliberately a plain {@link View}: the whole chart is one polyline plus the
 * axis frame, hour grid lines and day labels. Days without data contribute no
 * vertex, which is exactly "无数据的点跳过" — the line spans the gap.
 *
 * <p>The view never touches the database or threads: the fragment computes a
 * {@link StatsAggregator.Stats} off the main thread and calls {@link #setTrend}.
 */
public class SleepTrendView extends View {

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    private final float density;

    private List<StatsAggregator.WakePoint> points = new ArrayList<>();
    private LocalDate windowEnd = LocalDate.now();

    public SleepTrendView(Context context) {
        this(context, null);
    }

    public SleepTrendView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1f));
        axisPaint.setColor(color(R.color.line));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(color(R.color.line));

        labelPaint.setColor(color(R.color.muted));
        labelPaint.setTextSize(sp(9f));
        labelPaint.setTextAlign(Paint.Align.RIGHT);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(color(R.color.green_primary));

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(color(R.color.green_primary));

        hintPaint.setColor(color(R.color.muted));
        hintPaint.setTextSize(sp(13f));
        hintPaint.setTextAlign(Paint.Align.CENTER);

        setContentDescription(getContext().getString(R.string.stats_trend_a11y, 0));
    }

    /**
     * @param trend ascending wake-up points; empty is legal (the view shows a hint)
     * @param today right edge of the 14-day window; null falls back to the clock
     */
    public void setTrend(List<StatsAggregator.WakePoint> trend, @Nullable LocalDate today) {
        points = trend == null ? new ArrayList<>() : new ArrayList<>(trend);
        windowEnd = today == null ? LocalDate.now() : today;
        setContentDescription(getContext().getString(R.string.stats_trend_a11y, points.size()));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left = getPaddingLeft() + dp(34f);
        float top = getPaddingTop() + dp(10f);
        float right = getWidth() - getPaddingRight() - dp(6f);
        float bottom = getHeight() - getPaddingBottom() - dp(18f);
        if (right - left < dp(40f) || bottom - top < dp(40f)) {
            return; // measured too small to be readable
        }

        if (points.isEmpty()) {
            drawEmpty(canvas, left, top, right, bottom);
            return;
        }

        int minMinute = Integer.MAX_VALUE;
        int maxMinute = Integer.MIN_VALUE;
        for (StatsAggregator.WakePoint point : points) {
            minMinute = Math.min(minMinute, point.minuteOfDay);
            maxMinute = Math.max(maxMinute, point.minuteOfDay);
        }

        // Round outward to whole hours and keep at least a 2h band so a flat week
        // does not collapse into a straight line lying on the frame.
        int bottomHour = Math.max(0, minMinute / 60 - 1);
        int topHour = Math.min(24, maxMinute / 60 + 1);
        while (topHour - bottomHour < 2) {
            if (bottomHour > 0) {
                bottomHour--;
            } else {
                topHour++;
            }
        }

        float columnWidth = (right - left) / StatsAggregator.TREND_DAYS;
        drawAxes(canvas, left, top, right, bottom, bottomHour, topHour, columnWidth);

        int plotted = 0;
        linePath.reset();
        for (StatsAggregator.WakePoint point : points) {
            int dayIndex = dayIndex(point.date);
            if (dayIndex < 0) {
                continue; // outside the window: the query and the axis disagree
            }
            float x = left + (dayIndex + 0.5f) * columnWidth;
            float y = yFor(point.minuteOfDay, top, bottom, bottomHour, topHour);
            if (plotted == 0) {
                linePath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
            }
            plotted++;
        }
        if (plotted > 0) {
            canvas.drawPath(linePath, linePaint);
        }
        for (StatsAggregator.WakePoint point : points) {
            int dayIndex = dayIndex(point.date);
            if (dayIndex < 0) {
                continue;
            }
            float x = left + (dayIndex + 0.5f) * columnWidth;
            float y = yFor(point.minuteOfDay, top, bottom, bottomHour, topHour);
            canvas.drawCircle(x, y, dp(3f), pointPaint);
        }
    }

    /** Hour grid + labels, the axis frame, day separators and day-of-month labels. */
    private void drawAxes(Canvas canvas, float left, float top, float right, float bottom,
                          int bottomHour, int topHour, float columnWidth) {
        int hourSpan = topHour - bottomHour;
        int hourStep = hourSpan > 6 ? 2 : 1;
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();

        for (int hour = bottomHour; hour <= topHour; hour += hourStep) {
            float y = yFor(hour * 60, top, bottom, bottomHour, topHour);
            canvas.drawLine(left, y, right, y, gridPaint);
            String label = String.format(Locale.ROOT, "%02d:00", hour);
            canvas.drawText(label, left - dp(6f), y - (metrics.ascent + metrics.descent) / 2f,
                    labelPaint);
        }

        canvas.drawLine(left, top, left, bottom, axisPaint);
        canvas.drawLine(left, bottom, right, bottom, axisPaint);

        // One light vertical line every other day keeps 14 slots readable.
        for (int day = 0; day <= StatsAggregator.TREND_DAYS; day += 2) {
            float x = left + day * columnWidth;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }

        // Day-of-month labels every other slot (14 full dates would not fit).
        labelPaint.setTextAlign(Paint.Align.CENTER);
        for (int day = 0; day < StatsAggregator.TREND_DAYS; day += 2) {
            LocalDate date = windowEnd.minusDays(StatsAggregator.TREND_DAYS - 1L - day);
            float x = left + (day + 0.5f) * columnWidth;
            canvas.drawText(String.valueOf(date.getDayOfMonth()), x, bottom + sp(12f), labelPaint);
        }
        labelPaint.setTextAlign(Paint.Align.RIGHT);
    }

    private void drawEmpty(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.drawLine(left, top, left, bottom, axisPaint);
        canvas.drawLine(left, bottom, right, bottom, axisPaint);
        canvas.drawText(getContext().getString(R.string.stats_trend_empty),
                (left + right) / 2f, (top + bottom) / 2f, hintPaint);
    }

    /** Index from the left edge of the window (0..13), or -1 when outside it. */
    private int dayIndex(LocalDate date) {
        if (date == null) {
            return -1;
        }
        long offset = ChronoUnit.DAYS.between(
                windowEnd.minusDays(StatsAggregator.TREND_DAYS - 1L), date);
        return offset < 0 || offset >= StatsAggregator.TREND_DAYS ? -1 : (int) offset;
    }

    private float yFor(int minuteOfDay, float top, float bottom, int bottomHour, int topHour) {
        float spanMinutes = (topHour - bottomHour) * 60f;
        float normalized = (minuteOfDay - bottomHour * 60f) / spanMinutes;
        return bottom - normalized * (bottom - top);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private int color(int colorRes) {
        return ContextCompat.getColor(getContext(), colorRes);
    }
}
