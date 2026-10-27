package com.course.tongxiao.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.course.tongxiao.R;
import com.course.tongxiao.data.entity.Alarm;

import java.util.Locale;

/**
 * Step 3.1: alarm list rows (design.md §7 ①).
 *
 * <p>The switch listener is detached before {@code setChecked} so a recycled row
 * cannot fire a toggle for the alarm it used to show.
 */
public class AlarmListAdapter extends ListAdapter<Alarm, AlarmListAdapter.VH> {

    public interface OnEnabledChange {
        void onEnabledChange(@NonNull Alarm alarm, boolean enabled);
    }

    private static final DiffUtil.ItemCallback<Alarm> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull Alarm a, @NonNull Alarm b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Alarm a, @NonNull Alarm b) {
            return a.id == b.id
                    && a.hour == b.hour
                    && a.minute == b.minute
                    && a.enabled == b.enabled
                    && a.repeatMode.equals(b.repeatMode)
                    && a.skipHolidays == b.skipHolidays
                    && eq(a.label, b.label);
        }

        private boolean eq(String x, String y) {
            return x == null ? y == null : x.equals(y);
        }
    };

    private final OnEnabledChange listener;

    public AlarmListAdapter(OnEnabledChange listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alarm, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class VH extends RecyclerView.ViewHolder {
        private final android.widget.TextView time;
        private final android.widget.TextView label;
        private final android.widget.TextView rule;
        private final CompoundButton toggle;

        VH(@NonNull android.view.View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.alarm_time);
            label = itemView.findViewById(R.id.alarm_label);
            rule = itemView.findViewById(R.id.alarm_rule);
            toggle = itemView.findViewById(R.id.alarm_switch);
            ((android.view.View) toggle).setTag(R.id.alarm_switch, "bound");
        }

        void bind(Alarm alarm, OnEnabledChange listener) {
            Context ctx = itemView.getContext();
            time.setText(String.format(Locale.CHINA, "%02d:%02d", alarm.hour, alarm.minute));
            label.setText(alarm.label == null || alarm.label.isEmpty()
                    ? ctx.getString(R.string.alarm_default_label) : alarm.label);
            rule.setText(ruleText(ctx, alarm));
            itemView.setAlpha(alarm.enabled ? 1f : 0.5f);

            // Detach first: setChecked on a recycled row must not look like a user tap.
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(alarm.enabled);
            toggle.setOnCheckedChangeListener((button, isChecked) -> {
                if (listener != null) {
                    listener.onEnabledChange(alarm, isChecked);
                }
            });
        }

        private static String ruleText(Context ctx, Alarm alarm) {
            switch (alarm.repeatMode) {
                case ONCE:
                    return ctx.getString(R.string.rule_once);
                case DAILY:
                    return ctx.getString(R.string.rule_daily);
                case CUSTOM:
                    return ctx.getString(alarm.skipHolidays
                            ? R.string.rule_custom_skip_holiday : R.string.rule_custom);
                case WORKDAY_SMART:
                default:
                    return ctx.getString(R.string.rule_workday_smart);
            }
        }
    }
}
