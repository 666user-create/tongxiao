package com.course.tongxiao.alarm;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.repository.AlarmRepository;
import com.course.tongxiao.engine.HolidayCalendar;
import com.course.tongxiao.engine.HolidayResolver;
import com.course.tongxiao.engine.challenge.ChallengeConfig;
import com.course.tongxiao.group.AlarmGateway;
import com.course.tongxiao.group.protocol.GroupProtocol;

import org.json.JSONObject;

/**
 * Alarm-side implementation of the dorm-group seam (see {@link AlarmGateway}).
 *
 * <p>Two entry points, both called from the network reader thread:
 * <ul>
 *   <li>{@link #mute} — design.md §3.2: muting the ringing alarm needs no
 *       confirmation. Records source {@code remote:<deviceId>} so the operation
 *       trail is visible on the dorm screen;</li>
 *   <li>{@link #apply} — an owner-confirmed SET_REQUEST; the payload was already
 *       clamped on receipt, so here it is only materialised into Room + scheduler.</li>
 * </ul>
 * Every path returns a reason code instead of throwing: a bad remote message must
 * never crash the app that is currently ringing.
 */
public class AlarmGatewayImpl implements AlarmGateway {

    private static final String TAG = "AlarmGateway";

    private final Context appContext;
    private final TongxiaoDb db;
    private final AlarmRepository repository;
    private final AlarmScheduler scheduler;
    private final SettingsStore settings;

    public AlarmGatewayImpl(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.db = TongxiaoDb.get(appContext);
        this.repository = new AlarmRepository(db);
        HolidayResolver resolver = new HolidayResolver(
                HolidayCalendar.loadFromAssets(appContext));
        this.scheduler = new AlarmScheduler(appContext, repository, resolver);
        this.settings = new SettingsStore(appContext);
    }

    @Override
    public boolean isRinging(@NonNull String alarmUid) {
        String current = RingService.currentRingingUid();
        if (current != null && current.equals(alarmUid)) {
            return true;
        }
        // Fallback: between startForegroundService() and the DB read that resolves the
        // uid, currentRingingUid() is still null. The row id is known immediately, so
        // a mute arriving in that window still matches instead of answering
        // NOT_RINGING while the alarm keeps ringing.
        int ringingId = RingService.currentRingingAlarmId();
        if (ringingId <= 0) {
            return false;
        }
        Alarm alarm = db.alarmDao().getById(ringingId);
        return alarm != null && alarmUid.equals(alarm.uid);
    }

    @Nullable
    @Override
    public String mute(@NonNull String alarmUid, @NonNull String fromDeviceId) {
        try {
            Alarm alarm = db.alarmDao().getByUid(alarmUid);
            if (alarm == null) {
                return GroupProtocol.REASON_ALARM_NOT_FOUND;
            }
            if (!isRinging(alarmUid)) {
                return GroupProtocol.REASON_NOT_RINGING;
            }
            long startedAt = RingService.currentRingStartedAt();
            RingService.stop(appContext, alarm.id);
            new RingActions(db, repository, scheduler)
                    .onStopped(alarm, startedAt, "remote:" + fromDeviceId);
            Log.i(TAG, "remote mute by " + fromDeviceId + " for alarm " + alarmUid);
            return null;
        } catch (RuntimeException e) {
            Log.e(TAG, "mute failed for " + alarmUid, e);
            return GroupProtocol.REASON_INTERNAL_ERROR;
        }
    }

    @Nullable
    @Override
    public String apply(@NonNull PendingRequest request) {
        try {
            JSONObject payload = new JSONObject(request.payloadJson);
            switch (request.type) {
                case ADD_ALARM:
                    return applyAdd(payload);
                case REMOVE_ALARM:
                    return applyRemove(payload);
                case SET_CHALLENGE:
                    return applyChallenge(payload);
                default:
                    return GroupProtocol.REASON_BAD_PAYLOAD;
            }
        } catch (Exception e) {
            Log.e(TAG, "apply failed for " + request.reqId, e);
            return GroupProtocol.REASON_INTERNAL_ERROR;
        }
    }

    private String applyAdd(JSONObject payload) throws Exception {
        Alarm alarm = new Alarm();
        alarm.hour = payload.getInt("hour");
        alarm.minute = payload.getInt("minute");
        alarm.label = payload.optString("label", "");
        alarm.repeatMode = Alarm.RepeatMode.valueOf(payload.getString("repeatMode"));
        alarm.snoozeMin = payload.optInt("snoozeMin", 10);
        alarm.vibrate = payload.optBoolean("vibrate", true);
        JSONObject challenge = payload.optJSONObject("challenge");
        alarm.challengeOverrideJson = challenge == null ? null
                : ChallengeConfig.fromJson(challenge.toString()).clamped().toJson();
        alarm.enabled = true;
        alarm.id = (int) repository.insert(alarm);
        scheduler.schedule(alarm);
        Log.i(TAG, "remote ADD_ALARM applied: " + alarm.hour + ":" + alarm.minute);
        return null;
    }

    private String applyRemove(JSONObject payload) throws Exception {
        String uid = payload.optString("alarmUid", "").trim();
        Alarm alarm = uid.isEmpty() ? null : db.alarmDao().getByUid(uid);
        if (alarm == null) {
            return GroupProtocol.REASON_ALARM_NOT_FOUND;
        }
        scheduler.cancel(alarm);
        repository.deleteWithEvents(alarm);
        return null;
    }

    private String applyChallenge(JSONObject payload) throws Exception {
        JSONObject challenge = payload.optJSONObject("challenge");
        String normalized = challenge == null ? null
                : ChallengeConfig.fromJson(challenge.toString()).clamped().toJson();
        String uid = payload.optString("alarmUid", "").trim();
        if (uid.isEmpty()) {
            // Empty target = the app-wide default challenge config.
            settings.setDefaultChallengeJson(normalized);
            return null;
        }
        Alarm alarm = db.alarmDao().getByUid(uid);
        if (alarm == null) {
            return GroupProtocol.REASON_ALARM_NOT_FOUND;
        }
        alarm.challengeOverrideJson = normalized;
        repository.update(alarm);
        return null;
    }
}
