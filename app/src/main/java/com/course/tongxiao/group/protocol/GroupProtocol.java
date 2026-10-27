package com.course.tongxiao.group.protocol;

import androidx.annotation.Nullable;

/**
 * Wire vocabulary of the dorm-group protocol (design.md §4).
 *
 * <p>Everything that both ends must agree on lives here: framing limits, the
 * 6-digit group code format, and the {@code CMD_ACK.reason} values from the hard
 * bounds in design.md §3.2. Keeping the reason strings in one place is what makes
 * "the receiver enforces the rules" checkable — a sender cannot invent its own
 * reason vocabulary.
 */
public final class GroupProtocol {

    private GroupProtocol() {
    }

    /** NSD service type browsed and registered by every member (design.md §4). */
    public static final String SERVICE_TYPE = "_tongxiao._tcp";

    /** Messages are newline-delimited JSON: one line = one message (design.md §4). */
    public static final char LINE_SEPARATOR = '\n';

    /**
     * Hard cap for one JSON line. A single SYNC_PUSH for a normal dorm user is
     * well under 1 KB; the cap only exists so a broken or hostile peer cannot
     * grow our line buffer without bound. Oversize lines are dropped, the
     * connection survives (design.md §4 "错误处理").
     */
    public static final int MAX_LINE_BYTES = 8 * 1024;

    /** Group code = exactly 6 digits (design.md §4 "输入 6 位组码"). */
    public static final int GROUP_CODE_LENGTH = 6;

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    /** Device id is a UUID string; the cap only rejects junk from the wire. */
    public static final int MAX_DEVICE_ID_LENGTH = 64;

    // ---- CMD_ACK.reason codes (design.md §3.2 hard bounds) ----

    /** Settings-page master switch is OFF: no remote command is executed at all. */
    public static final String REASON_MASTER_SWITCH_OFF = "MASTER_SWITCH_OFF";
    /** The sender's granted mask does not cover this operation. */
    public static final String REASON_PERMISSION_DENIED = "PERMISSION_DENIED";
    /** CMD_MUTE target uid is unknown on this device (peer should re-send SYNC_PUSH). */
    public static final String REASON_ALARM_NOT_FOUND = "ALARM_NOT_FOUND";
    /** CMD_MUTE only applies to an alarm that is ringing right now (design.md §3.2). */
    public static final String REASON_NOT_RINGING = "NOT_RINGING";
    /** No heartbeat from that peer within the freshness window (design.md §3.2). */
    public static final String REASON_STALE_LINK = "STALE_LINK";
    /** Structurally invalid payload; the receiver refuses to guess. */
    public static final String REASON_BAD_PAYLOAD = "BAD_PAYLOAD";
    /** Same reqId already resolved (or owned by another sender): not applied again. */
    public static final String REASON_DUPLICATE_REQUEST = "DUPLICATE_REQUEST";
    /** HELLO group code does not match this device's group (design.md §4). */
    public static final String REASON_GROUP_KEY_MISMATCH = "GROUP_KEY_MISMATCH";
    /** This device is not in a group, so no handshake can succeed. */
    public static final String REASON_NOT_IN_GROUP = "NOT_IN_GROUP";
    /** The alarm side failed unexpectedly while executing; nothing was applied. */
    public static final String REASON_INTERNAL_ERROR = "INTERNAL_ERROR";

    /** True when {@code code} is exactly {@value #GROUP_CODE_LENGTH} ASCII digits. */
    public static boolean isValidGroupCode(@Nullable String code) {
        if (code == null || code.length() != GROUP_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** TCP port range check, used before dialling a discovered peer. */
    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    /** Rejects empty/oversized device ids coming from the wire (design.md §4 HELLO). */
    public static boolean isValidDeviceId(@Nullable String deviceId) {
        if (deviceId == null) {
            return false;
        }
        String trimmed = deviceId.trim();
        return !trimmed.isEmpty() && trimmed.length() <= MAX_DEVICE_ID_LENGTH;
    }
}
