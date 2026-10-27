package com.course.tongxiao.group;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receiver-side protocol policy of the dorm group (design.md §3.2, §4).
 *
 * <p>This class is the security boundary: every message that changes local state
 * is checked <b>on the receiving device</b> against the persisted
 * {@code grantedMask} and the settings-page master switch. The sender is never
 * trusted (design.md §8.2 "不信任发起端"), and the checks are independent of any
 * sender-side UI.
 *
 * <p>Permission model of design.md §3.1/§3.2 — {@code group_members.grantedMask}
 * is what <em>I</em> allow that member to do:
 * <ul>
 *   <li>bit0 {@link GroupMember#PERM_MUTE} — mute my ringing alarm (no confirmation);</li>
 *   <li>bit1 {@link GroupMember#PERM_SEE} — schedule sharing with that member
 *       (gates SYNC_PUSH in both directions in v1: one bit per relationship);</li>
 *   <li>bit2 {@link GroupMember#PERM_SET} — send me setting requests. Even with
 *       the bit, nothing is applied until the owner confirms (pending_requests).</li>
 * </ul>
 *
 * <p>No sockets and no Android services: replies leave through {@link Reply},
 * alarm-side effects through {@link AlarmGateway}. Everything here is therefore
 * testable against an in-memory DB.
 *
 * <p>Threading: called on the owning {@code PeerLink}'s reader thread; all DB
 * access is Room on a background thread.
 */
public class GroupCoordinator {

    private static final String TAG = "GroupCoordinator";

    /** Pending requests expire after 2h unattended (design.md §3.1/§3.3). */
    static final long PENDING_TTL_MS = 2L * 60 * 60 * 1000;

    /** CMD_MUTE requires a heartbeat within the last 60s (design.md §3.2 hard bound). */
    static final long LINK_FRESHNESS_MS = 60_000L;

    /** Cap for one SYNC_PUSH: a mirror of a dorm schedule is tiny; junk is not. */
    static final int MAX_SYNC_ALARMS = 200;

    private static final int MAX_NICK_LENGTH = 20;
    private static final int MAX_TEXT_LENGTH = 40;

    private static final String SYNC_FIELD_UID = "uid";

    /** Sends a message back to the peer that produced the current inbound message. */
    public interface Reply {
        void send(@NonNull GroupMessage message);
    }

    private final TongxiaoDb db;
    private final SettingsStore settings;
    private final GroupIdentity identity;
    private final AlarmGateway gateway;
    private final PeerStatsStore peerStats;
    /** STATS_PUSH packets that were not stored (invalid, unknown sender, master off). */
    private final AtomicInteger droppedStats = new AtomicInteger();

    public GroupCoordinator(@NonNull TongxiaoDb db, @NonNull SettingsStore settings,
                            @NonNull GroupIdentity identity, @NonNull AlarmGateway gateway,
                            @NonNull PeerStatsStore peerStats) {
        this.db = db;
        this.settings = settings;
        this.identity = identity;
        this.gateway = gateway;
        this.peerStats = peerStats;
    }

    /** Test/observability hook: how many STATS_PUSH packets were thrown away. */
    public int droppedStats() {
        return droppedStats.get();
    }

    // ---- handshake ----

    /**
     * Validates a HELLO and enrols the sender (design.md §4 "握手，验组码").
     *
     * <p>A new member starts with mute + schedule-sharing granted (the
     * frictionless tier of §3.2) and <b>no</b> setting permission: changing my
     * alarms stays an explicit grant, so a peer cannot reach the confirmation
     * queue until I allow it.
     *
     * @return null when the handshake is acceptable, otherwise the reason code
     */
    @Nullable
    public String onHello(@NonNull GroupMessage hello) {
        String deviceId = hello.string(GroupMessage.FIELD_DEV, "").trim();
        if (!GroupProtocol.isValidDeviceId(deviceId)) {
            return GroupProtocol.REASON_BAD_PAYLOAD;
        }
        String myCode = identity.groupCode();
        if (myCode.isEmpty()) {
            return GroupProtocol.REASON_NOT_IN_GROUP;
        }
        if (!myCode.equals(hello.string(GroupMessage.FIELD_GROUP, "").trim())) {
            return GroupProtocol.REASON_GROUP_KEY_MISMATCH;
        }
        if (deviceId.equals(identity.deviceId())) {
            return GroupProtocol.REASON_BAD_PAYLOAD; // our own NSD record looped back
        }

        String nick = RequestPayloads.trim(hello.string(GroupMessage.FIELD_NICK, ""),
                MAX_NICK_LENGTH);
        long now = System.currentTimeMillis();
        GroupMember existing = db.groupMemberDao().getByDeviceId(deviceId);
        if (existing == null) {
            GroupMember member = new GroupMember();
            member.deviceId = deviceId;
            member.nick = nick.isEmpty() ? deviceId.substring(0, Math.min(8, deviceId.length())) : nick;
            member.groupKey = myCode;
            member.grantedMask = GroupMember.PERM_MUTE | GroupMember.PERM_SEE;
            member.online = true;
            member.lastSeenAt = now;
            member.joinAt = now;
            db.groupMemberDao().upsert(member);
        } else {
            if (!nick.isEmpty()) {
                db.groupMemberDao().updateProfile(deviceId, nick, myCode);
            }
            db.groupMemberDao().setOnline(deviceId, true, now);
        }
        return null;
    }

    // ---- commands ----

    /**
     * Handles every message that may change local state. Messages that only the
     * initiating side acts on (CMD_ACK, NOTIFY_OP, SET_RESPONSE) are ignored here:
     * they carry no receiver-side effect in v1 and the UI shows them through
     * {@code GroupManager}'s observer.
     */
    public void onCommand(@NonNull String senderDeviceId, @NonNull GroupMessage message,
                          @NonNull Reply reply) {
        switch (message.type()) {
            case CMD_MUTE:
                handleMute(senderDeviceId, message, reply);
                break;
            case SYNC_PUSH:
                handleSyncPush(senderDeviceId, message, reply);
                break;
            case SET_REQUEST:
                handleSetRequest(senderDeviceId, message, reply);
                break;
            case STATS_PUSH:
                handleStatsPush(senderDeviceId, message);
                break;
            default:
                break;
        }
    }

    /** design.md §3.2: mute is the only no-confirmation operation. */
    private void handleMute(@NonNull String sender, @NonNull GroupMessage message,
                            @NonNull Reply reply) {
        if (!settings.isGroupMasterSwitchOn()) {
            ack(reply, false, GroupProtocol.REASON_MASTER_SWITCH_OFF);
            return;
        }
        GroupMember member = db.groupMemberDao().getByDeviceId(sender);
        if (member == null || (member.grantedMask & GroupMember.PERM_MUTE) == 0) {
            ack(reply, false, GroupProtocol.REASON_PERMISSION_DENIED);
            return;
        }
        if (!isLinkFresh(member)) {
            ack(reply, false, GroupProtocol.REASON_STALE_LINK);
            return;
        }
        String owner = message.string(GroupMessage.FIELD_OWNER_DEV, "").trim();
        String uid = message.string(GroupMessage.FIELD_ALARM_UID, "").trim();
        if (uid.isEmpty()) {
            ack(reply, false, GroupProtocol.REASON_BAD_PAYLOAD);
            return;
        }
        // A peer may only mute its own ringing alarm; otherwise one member could
        // silence another member's alarm through a third device.
        if (!sender.equals(owner)) {
            ack(reply, false, GroupProtocol.REASON_PERMISSION_DENIED);
            return;
        }
        String failure;
        try {
            failure = gateway.mute(uid, sender);
        } catch (RuntimeException broken) {
            Log.e(TAG, "mute failed for uid " + uid, broken);
            failure = GroupProtocol.REASON_INTERNAL_ERROR;
        }
        if (failure != null) {
            ack(reply, false, failure);
            return;
        }
        ack(reply, true, null);
        // Receipt for the sender's op-log ("你静音了小李的 06:40"), design.md §4.
        reply.send(GroupMessage.notifyOp("MUTE " + uid, System.currentTimeMillis()));
    }

    /** design.md §4 SYNC_PUSH: full replacement of one owner's schedule mirror. */
    private void handleSyncPush(@NonNull String sender, @NonNull GroupMessage message,
                                @NonNull Reply reply) {
        if (!settings.isGroupMasterSwitchOn()) {
            ack(reply, false, GroupProtocol.REASON_MASTER_SWITCH_OFF);
            return;
        }
        GroupMember member = db.groupMemberDao().getByDeviceId(sender);
        if (member == null || (member.grantedMask & GroupMember.PERM_SEE) == 0) {
            ack(reply, false, GroupProtocol.REASON_PERMISSION_DENIED);
            return;
        }
        JSONArray array = message.array(GroupMessage.FIELD_ALARMS);
        if (array == null) {
            ack(reply, false, GroupProtocol.REASON_BAD_PAYLOAD);
            return;
        }
        long now = System.currentTimeMillis();
        List<RemoteAlarm> rows = new ArrayList<>(array.length());
        int limit = Math.min(array.length(), MAX_SYNC_ALARMS);
        for (int i = 0; i < limit; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String uid = item.optString(SYNC_FIELD_UID, "").trim();
            if (uid.isEmpty()) {
                continue; // a row we cannot address is useless for CMD_MUTE
            }
            RemoteAlarm row = new RemoteAlarm();
            row.srcAlarmUid = uid;
            row.alarmLabel = RequestPayloads.trim(item.optString(
                    GroupMessage.FIELD_ALARM_LABEL, ""), MAX_TEXT_LENGTH);
            row.timeText = RequestPayloads.trim(item.optString(
                    GroupMessage.FIELD_ALARM_TIME, ""), MAX_TEXT_LENGTH);
            row.repeatText = RequestPayloads.trim(item.optString(
                    GroupMessage.FIELD_ALARM_REPEAT, ""), MAX_TEXT_LENGTH);
            row.enabled = item.optBoolean(GroupMessage.FIELD_ALARM_ENABLED, false);
            row.ringingNow = item.optBoolean(GroupMessage.FIELD_ALARM_RINGING, false);
            row.syncedAt = now;
            rows.add(row);
        }
        // replaceOwner force-tags every row with the sender's id (design.md §3.1),
        // so a stray row cannot pollute another owner's mirror.
        db.remoteAlarmDao().replaceOwner(sender, rows);
        ack(reply, true, null);
    }

    /** design.md §3.2: setting-class requests are queued, never applied on receipt. */
    private void handleSetRequest(@NonNull String sender, @NonNull GroupMessage message,
                                  @NonNull Reply reply) {
        if (!settings.isGroupMasterSwitchOn()) {
            ack(reply, false, GroupProtocol.REASON_MASTER_SWITCH_OFF);
            return;
        }
        GroupMember member = db.groupMemberDao().getByDeviceId(sender);
        if (member == null || (member.grantedMask & GroupMember.PERM_SET) == 0) {
            ack(reply, false, GroupProtocol.REASON_PERMISSION_DENIED);
            return;
        }
        String reqId = message.string(GroupMessage.FIELD_REQ_ID, "").trim();
        PendingRequest.Kind kind = RequestPayloads.kindOf(message.string(GroupMessage.FIELD_KIND, ""));
        JSONObject payload = message.object(GroupMessage.FIELD_PAYLOAD);
        if (reqId.isEmpty() || kind == null || payload == null) {
            ack(reply, false, GroupProtocol.REASON_BAD_PAYLOAD);
            return;
        }
        JSONObject normalized = RequestPayloads.normalize(kind, payload);
        if (normalized == null) {
            ack(reply, false, GroupProtocol.REASON_BAD_PAYLOAD);
            return;
        }

        long now = System.currentTimeMillis();
        PendingRequest request = new PendingRequest();
        request.reqId = reqId;
        request.fromDeviceId = sender;
        request.type = kind;
        request.payloadJson = normalized.toString();
        request.createdAt = now;
        request.expireAt = now + PENDING_TTL_MS;
        request.status = PendingRequest.Status.PENDING;

        long rowId = db.pendingRequestDao().insert(request);
        if (rowId == -1L) {
            // Duplicate delivery is idempotent; a re-broadcast of an already
            // resolved reqId must not resurrect it (PendingRequestDao#insert).
            PendingRequest existing = db.pendingRequestDao().getByReqId(reqId);
            boolean sameSenderPending = existing != null
                    && existing.status == PendingRequest.Status.PENDING
                    && sender.equals(existing.fromDeviceId);
            ack(reply, sameSenderPending,
                    sameSenderPending ? null : GroupProtocol.REASON_DUPLICATE_REQUEST);
            return;
        }
        ack(reply, true, null);
    }

    /**
     * STATS_PUSH: a peer broadcasting its own leaderboard snapshot.
     *
     * <p>No permission bit is required — this is read-only hall information that any
     * member of the group may publish about itself (design.md §3.2 only guards
     * operations that touch <em>my</em> alarms). Two rules still apply:
     * <ul>
     *   <li>the settings-page master switch off means "no remote traffic at all", so
     *       the packet is dropped like every other command;</li>
     *   <li>the claimed identity is overwritten with the authenticated sender, so one
     *       member cannot forge another member's ranking row (design.md §8.2).</li>
     * </ul>
     * Nothing is acknowledged: a broadcast is best-effort by design — the next
     * snapshot heals the gap (offline-first / eventual consistency).
     */
    private void handleStatsPush(@NonNull String sender, @NonNull GroupMessage message) {
        if (!settings.isGroupMasterSwitchOn()) {
            droppedStats.incrementAndGet();
            return;
        }
        GroupMember member = db.groupMemberDao().getByDeviceId(sender);
        if (member == null) {
            // Only members of my group may write into my leaderboard cache.
            droppedStats.incrementAndGet();
            return;
        }
        JSONObject payload = message.object(GroupMessage.FIELD_PAYLOAD);
        if (payload == null) {
            droppedStats.incrementAndGet();
            return;
        }
        try {
            JSONObject trusted = new JSONObject(payload.toString());
            trusted.put(GroupMessage.FIELD_STATS_DEVICE_ID, sender);
            if (RequestPayloads.trim(
                    trusted.optString(GroupMessage.FIELD_NICK, ""), MAX_NICK_LENGTH).isEmpty()) {
                trusted.put(GroupMessage.FIELD_NICK, member.nick);
            }
            if (peerStats.normalizeAndStore(trusted) == null) {
                droppedStats.incrementAndGet();
            }
        } catch (JSONException broken) {
            Log.w(TAG, "unreadable STATS_PUSH from " + sender, broken);
            droppedStats.incrementAndGet();
        }
    }

    // ---- pending requests (design.md §3.1/§5.5) ----

    /**
     * Owner accepts a pending request: the payload is applied through the alarm
     * layer and only then marked ACCEPTED.
     *
     * <p>The request stays PENDING while its sender is offline — being offline is
     * not a reason to guess the owner's decision (design.md §3.2).
     *
     * @return the accepted request so the caller can send SET_RESPONSE, or null
     *         when it is unknown, already resolved or expired
     */
    @Nullable
    public PendingRequest accept(@NonNull String reqId) {
        PendingRequest request = db.pendingRequestDao().getByReqId(reqId);
        if (request == null || request.status != PendingRequest.Status.PENDING) {
            return null;
        }
        if (request.expireAt < System.currentTimeMillis()) {
            db.pendingRequestDao().setStatus(reqId, PendingRequest.Status.EXPIRED);
            return null;
        }
        String failure;
        try {
            failure = gateway.apply(request);
        } catch (RuntimeException broken) {
            Log.e(TAG, "apply failed for request " + reqId, broken);
            failure = GroupProtocol.REASON_INTERNAL_ERROR;
        }
        if (failure != null) {
            Log.w(TAG, "request " + reqId + " could not be applied: " + failure);
            db.pendingRequestDao().setStatus(reqId, PendingRequest.Status.REJECTED);
            return null;
        }
        db.pendingRequestDao().setStatus(reqId, PendingRequest.Status.ACCEPTED);
        return request;
    }

    /** Owner declines: nothing is applied. @return the rejected request, or null. */
    @Nullable
    public PendingRequest reject(@NonNull String reqId) {
        PendingRequest request = db.pendingRequestDao().getByReqId(reqId);
        if (request == null || request.status != PendingRequest.Status.PENDING) {
            return null;
        }
        db.pendingRequestDao().setStatus(reqId, PendingRequest.Status.REJECTED);
        return request;
    }

    /** Void-until-decided sweep, run on app start and before the dorm page renders. */
    public int expireOverdue() {
        return db.pendingRequestDao().expireOverdue(PendingRequest.Status.PENDING,
                PendingRequest.Status.EXPIRED, System.currentTimeMillis());
    }

    // ---- outbound schedule push ----

    /**
     * Builds the SYNC_PUSH this device shares with its peers (design.md §4).
     * Only alarms the user explicitly shared are included; {@code ringing} lets
     * the peer enable its mute button.
     */
    @NonNull
    public GroupMessage buildSyncPush() {
        JSONArray alarms = new JSONArray();
        for (Alarm alarm : db.alarmDao().getAll()) {
            if (!alarm.sharedToGroup) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put(SYNC_FIELD_UID, alarm.uid);
                item.put(GroupMessage.FIELD_ALARM_LABEL,
                        RequestPayloads.trim(alarm.label, MAX_TEXT_LENGTH));
                item.put(GroupMessage.FIELD_ALARM_TIME, String.format(Locale.US,
                        "%02d:%02d", alarm.hour, alarm.minute));
                item.put(GroupMessage.FIELD_ALARM_REPEAT, repeatText(alarm.repeatMode));
                item.put(GroupMessage.FIELD_ALARM_ENABLED, alarm.enabled);
                item.put(GroupMessage.FIELD_ALARM_RINGING, isRinging(alarm.uid));
            } catch (JSONException impossible) {
                continue; // skip the row rather than send a half-built entry
            }
            alarms.put(item);
        }
        return GroupMessage.syncPush(alarms);
    }

    private boolean isRinging(@NonNull String alarmUid) {
        try {
            return gateway.isRinging(alarmUid);
        } catch (RuntimeException broken) {
            Log.e(TAG, "isRinging failed for " + alarmUid, broken);
            return false;
        }
    }

    @NonNull
    private static String repeatText(@NonNull Alarm.RepeatMode mode) {
        switch (mode) {
            case ONCE:
                return "一次";
            case DAILY:
                return "每天";
            case CUSTOM:
                return "自定义星期";
            case WORKDAY_SMART:
            default:
                return "智能工作日";
        }
    }

    private static void ack(@NonNull Reply reply, boolean ok, @Nullable String reason) {
        reply.send(GroupMessage.cmdAck(ok, reason));
    }

    /**
     * design.md §3.2: "静音指令 ... 要求 TCP 连接 60s 内有心跳". The member row's
     * lastSeenAt is refreshed by every inbound message, so this rejects a command
     * that arrives long after the link stopped delivering heartbeats.
     */
    private static boolean isLinkFresh(@NonNull GroupMember member) {
        return System.currentTimeMillis() - member.lastSeenAt <= LINK_FRESHNESS_MS;
    }
}
