package com.course.tongxiao.group.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One protocol message (design.md §4 table): a JSON object carrying a
 * {@code type} plus type-specific fields.
 *
 * <p>Deliberately a thin typed wrapper over the parsed JSON instead of nine
 * subclasses: the payload shapes are small, and the receiver must tolerate
 * missing/extra fields anyway (never trust a sender, design.md §8.2).
 *
 * <p>Field names are fixed by the constants below so codec tests, the
 * coordinator and the factories cannot drift apart.
 */
public final class GroupMessage {

    /** Message kinds of design.md §4. Order matches the design table. */
    public enum Type {
        /** Handshake; validates the 6-digit group code. */
        HELLO,
        /** Graceful disconnect. */
        BYE,
        /** My schedule snapshot (requires the peer's bit1 visibility grant). */
        SYNC_PUSH,
        /** Mute the alarm that is ringing right now (no confirmation, §3.2). */
        CMD_MUTE,
        /** Result of a command. */
        CMD_ACK,
        /** Operation receipt for the sender's op-log ("你静音了小李的 06:40"). */
        NOTIFY_OP,
        /** Setting-class request: needs the owner's confirmation (§3.2). */
        SET_REQUEST,
        /** Owner's answer to a SET_REQUEST. */
        SET_RESPONSE,
        /** 60s heartbeat. */
        PING,
        /**
         * Leaderboard snapshot of the sender's own wake-up statistics.
         *
         * <p>Appended after the design.md §4 table on purpose: it is an additive
         * message type, and an older build simply ignores it as an unknown type
         * (design.md §4 错误处理), so no version negotiation is needed.
         */
        STATS_PUSH;

        /** @return the constant for a wire value, or null when unknown (§4: ignore). */
        @Nullable
        static Type fromWire(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            for (Type candidate : values()) {
                if (candidate.name().equals(raw)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    // ---- field names (design.md §4 payload column) ----
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_DEV = "dev";
    public static final String FIELD_NICK = "nick";
    public static final String FIELD_GROUP = "group";
    public static final String FIELD_TS = "ts";
    public static final String FIELD_ALARMS = "alarms";
    public static final String FIELD_OWNER_DEV = "ownerDev";
    public static final String FIELD_ALARM_UID = "alarmUid";
    public static final String FIELD_OK = "ok";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_WHAT = "what";
    public static final String FIELD_AT = "at";
    public static final String FIELD_REQ_ID = "reqId";
    public static final String FIELD_KIND = "kind";
    public static final String FIELD_PAYLOAD = "payload";
    public static final String FIELD_ACCEPT = "accept";

    // ---- SYNC_PUSH alarm fields (design.md §4: uid,label,time,repeat,enabled,ringing) ----
    public static final String FIELD_ALARM_LABEL = "label";
    public static final String FIELD_ALARM_TIME = "time";
    public static final String FIELD_ALARM_REPEAT = "repeat";
    public static final String FIELD_ALARM_ENABLED = "enabled";
    public static final String FIELD_ALARM_RINGING = "ringing";

    // ---- STATS_PUSH snapshot fields (leaderboard, offline-first) ----
    public static final String FIELD_STATS_DEVICE_ID = "deviceId";
    public static final String FIELD_STATS_DAYS = "days";
    public static final String FIELD_STATS_MEDIAN_STOP = "medianStopMinute";
    public static final String FIELD_STATS_SNOOZE = "snoozeCount";
    public static final String FIELD_STATS_MUTED = "mutedCount";
    public static final String FIELD_STATS_SENT_AT = "sentAt";

    private final Type type;
    private final JSONObject body;

    private GroupMessage(@NonNull Type type, @NonNull JSONObject body) {
        this.type = type;
        this.body = body;
    }

    @NonNull
    public Type type() {
        return type;
    }

    // ---- parsing ----

    /**
     * @return the message, or null when {@code type} is missing or unknown.
     *         {@link MessageCodec} counts that case separately from malformed
     *         JSON for logging; both are ignored without dropping the peer
     *         (design.md §4 "错误处理").
     */
    @Nullable
    public static GroupMessage fromJson(@NonNull JSONObject root) {
        Type parsed = Type.fromWire(root.optString(FIELD_TYPE, null));
        return parsed == null ? null : new GroupMessage(parsed, root);
    }

    /** Wire form without the newline: {@link MessageCodec} owns framing. */
    @NonNull
    public String toJson() {
        return body.toString();
    }

    // ---- field access (defensive defaults, never throws) ----

    @NonNull
    public String string(@NonNull String key, @NonNull String fallback) {
        String value = body.optString(key, fallback);
        return value == null ? fallback : value;
    }

    public boolean bool(@NonNull String key, boolean fallback) {
        return body.optBoolean(key, fallback);
    }

    public int intValue(@NonNull String key, int fallback) {
        return body.optInt(key, fallback);
    }

    public long longValue(@NonNull String key, long fallback) {
        return body.optLong(key, fallback);
    }

    @Nullable
    public JSONArray array(@NonNull String key) {
        return body.optJSONArray(key);
    }

    @Nullable
    public JSONObject object(@NonNull String key) {
        return body.optJSONObject(key);
    }

    @NonNull
    @Override
    public String toString() {
        return "GroupMessage{" + type + "}";
    }

    // ---- factories ----

    @NonNull
    public static GroupMessage hello(@NonNull String deviceId, @NonNull String nick,
                                     @NonNull String groupCode) {
        return build(Type.HELLO, FIELD_DEV, deviceId, FIELD_NICK, nick, FIELD_GROUP, groupCode);
    }

    @NonNull
    public static GroupMessage bye() {
        return build(Type.BYE);
    }

    @NonNull
    public static GroupMessage ping(long timestamp) {
        return build(Type.PING, FIELD_TS, timestamp);
    }

    @NonNull
    public static GroupMessage syncPush(@NonNull JSONArray alarms) {
        return build(Type.SYNC_PUSH, FIELD_ALARMS, alarms);
    }

    @NonNull
    public static GroupMessage cmdMute(@NonNull String ownerDeviceId, @NonNull String alarmUid) {
        return build(Type.CMD_MUTE, FIELD_OWNER_DEV, ownerDeviceId, FIELD_ALARM_UID, alarmUid);
    }

    /** @param reason null when {@code ok}, otherwise a {@link GroupProtocol} reason code. */
    @NonNull
    public static GroupMessage cmdAck(boolean ok, @Nullable String reason) {
        return build(Type.CMD_ACK, FIELD_OK, ok, FIELD_REASON, reason == null ? "" : reason);
    }

    @NonNull
    public static GroupMessage notifyOp(@NonNull String what, long at) {
        return build(Type.NOTIFY_OP, FIELD_WHAT, what, FIELD_AT, at);
    }

    @NonNull
    public static GroupMessage setRequest(@NonNull String reqId, @NonNull String kind,
                                          @NonNull JSONObject payload) {
        return build(Type.SET_REQUEST, FIELD_REQ_ID, reqId, FIELD_KIND, kind, FIELD_PAYLOAD, payload);
    }

    @NonNull
    public static GroupMessage setResponse(@NonNull String reqId, boolean accept) {
        return build(Type.SET_RESPONSE, FIELD_REQ_ID, reqId, FIELD_ACCEPT, accept);
    }

    /**
     * Leaderboard snapshot. The snapshot fields travel nested under
     * {@link #FIELD_PAYLOAD} (same envelope shape as SET_REQUEST) and are
     * re-validated by the receiver — the sender is never trusted.
     */
    @NonNull
    public static GroupMessage statsPush(@NonNull JSONObject snapshot) {
        return build(Type.STATS_PUSH, FIELD_PAYLOAD, snapshot);
    }

    // JSONObject.put(String, Object) only throws for non-finite numbers; every value
    // here comes from the caller's own strings/flags, so this cannot fail in practice.
    private static GroupMessage build(@NonNull Type type, Object... keyValues) {
        JSONObject body = new JSONObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                continue;
            }
            try {
                body.put(key, value);
            } catch (JSONException impossible) {
                throw new IllegalArgumentException("unencodable field " + key, impossible);
            }
        }
        try {
            body.put(FIELD_TYPE, type.name());
        } catch (JSONException impossible) {
            throw new IllegalStateException("cannot encode type", impossible);
        }
        return new GroupMessage(type, body);
    }
}
