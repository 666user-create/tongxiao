package com.course.tongxiao.group;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.engine.challenge.ChallengeConfig;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receiver-side clamping of SET_REQUEST payloads (design.md §3.2 "硬边界":
 * "不信任发起端", all remote parameters are bounded on the receiving device).
 *
 * <p>Two different answers are used on purpose:
 * <ul>
 *   <li><b>Clamp</b> values where any in-range value is a sane alarm ("snooze 999"
 *       becomes 30) — {@link ChallengeConfig#clamped()} is applied to every
 *       challenge block, so question count / delay / shake count / types can never
 *       exceed what the local editor could produce;</li>
 *   <li><b>Reject</b> when the payload is structurally unusable (hour outside 0-23,
 *       unknown repeat mode, missing ids): guessing here would create an alarm the
 *       sender never asked for, so the request is refused with
 *       {@link com.course.tongxiao.group.protocol.GroupProtocol#REASON_BAD_PAYLOAD}.</li>
 * </ul>
 *
 * <p>The normalized JSON (never the raw payload) is what lands in
 * {@code pending_requests.payloadJson}, so what the owner confirms is exactly what
 * the receiver allows (design.md §3.1).
 */
final class RequestPayloads {

    static final int MAX_LABEL_LENGTH = 40;
    static final int MIN_SNOOZE_MIN = 1;
    static final int MAX_SNOOZE_MIN = 30;

    private static final String KEY_ALARM_UID = "alarmUid";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_LABEL = "label";
    private static final String KEY_REPEAT_MODE = "repeatMode";
    private static final String KEY_SNOOZE_MIN = "snoozeMin";
    private static final String KEY_VIBRATE = "vibrate";
    private static final String KEY_CHALLENGE = "challenge";

    private RequestPayloads() {
    }

    /** @return the parsed request kind, or null when the wire value is unknown. */
    @Nullable
    static PendingRequest.Kind kindOf(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        for (PendingRequest.Kind candidate : PendingRequest.Kind.values()) {
            if (candidate.name().equals(raw.trim())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @return the clamped payload to store, or null when the request cannot be
     *         normalized safely (the caller answers BAD_PAYLOAD)
     */
    @Nullable
    static JSONObject normalize(@NonNull PendingRequest.Kind kind, @NonNull JSONObject payload) {
        try {
            switch (kind) {
                case SET_CHALLENGE:
                    return normalizeChallenge(payload);
                case ADD_ALARM:
                    return normalizeAddedAlarm(payload);
                case REMOVE_ALARM:
                    return normalizeRemoval(payload);
                default:
                    return null;
            }
        } catch (JSONException broken) {
            return null;
        }
    }

    /**
     * Challenge edit: {@code {alarmUid?: string, challenge: {...}}}. An empty
     * alarmUid targets the app-wide default challenge config; a non-empty one
     * targets that alarm's override (design.md §3.1).
     */
    @Nullable
    private static JSONObject normalizeChallenge(@NonNull JSONObject payload) throws JSONException {
        JSONObject challenge = payload.optJSONObject(KEY_CHALLENGE);
        if (challenge == null) {
            return null; // nothing to apply
        }
        JSONObject out = new JSONObject();
        out.put(KEY_ALARM_UID, payload.optString(KEY_ALARM_UID, "").trim());
        out.put(KEY_CHALLENGE, clampedChallenge(challenge));
        return out;
    }

    /** New alarm: time and repeat mode are required; everything else is clamped. */
    @Nullable
    private static JSONObject normalizeAddedAlarm(@NonNull JSONObject payload) throws JSONException {
        int hour = payload.optInt(KEY_HOUR, -1);
        int minute = payload.optInt(KEY_MINUTE, -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null; // cannot guess a time the peer did not state
        }
        String repeatMode = payload.optString(KEY_REPEAT_MODE, "");
        String safeMode = null;
        for (Alarm.RepeatMode mode : Alarm.RepeatMode.values()) {
            if (mode.name().equals(repeatMode.trim())) {
                safeMode = mode.name();
            }
        }
        if (safeMode == null) {
            return null; // unknown repeat rule would silently change firing days
        }
        JSONObject out = new JSONObject();
        out.put(KEY_HOUR, hour);
        out.put(KEY_MINUTE, minute);
        out.put(KEY_REPEAT_MODE, safeMode);
        out.put(KEY_LABEL, trim(payload.optString(KEY_LABEL, ""), MAX_LABEL_LENGTH));
        out.put(KEY_SNOOZE_MIN, clamp(payload.optInt(KEY_SNOOZE_MIN, 10),
                MIN_SNOOZE_MIN, MAX_SNOOZE_MIN));
        out.put(KEY_VIBRATE, payload.optBoolean(KEY_VIBRATE, true));
        JSONObject challenge = payload.optJSONObject(KEY_CHALLENGE);
        if (challenge != null) {
            out.put(KEY_CHALLENGE, clampedChallenge(challenge));
        }
        return out;
    }

    /** Removal: needs the target uid; nothing else is meaningful. */
    @Nullable
    private static JSONObject normalizeRemoval(@NonNull JSONObject payload) throws JSONException {
        String uid = payload.optString(KEY_ALARM_UID, "").trim();
        if (uid.isEmpty()) {
            return null;
        }
        JSONObject out = new JSONObject();
        out.put(KEY_ALARM_UID, uid);
        return out;
    }

    /**
     * Every challenge block is re-clamped here — the single enforcement point for
     * design.md §3.2 ("题数≤5 ... 越界按接收端规则处理", §5.4 "接收端强制钳制").
     */
    @NonNull
    private static JSONObject clampedChallenge(@NonNull JSONObject challenge) throws JSONException {
        ChallengeConfig clamped = ChallengeConfig.fromJson(challenge.toString()).clamped();
        return new JSONObject(clamped.toJson());
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Trims and cuts to {@code maxLength} characters (defensive against huge labels). */
    @NonNull
    static String trim(@Nullable String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
