package com.course.tongxiao.group;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.course.tongxiao.group.protocol.GroupProtocol;

import java.util.UUID;

/**
 * Stable local identity of this device inside the dorm group (design.md §3.1
 * {@code deviceId}, §4 "实例名=昵称" + 6-digit group code).
 *
 * <p>Why a separate prefs file: {@code data/SettingsStore} owns the app-wide
 * preference keys of design.md §3.1 and is somebody else's file; the group keys
 * (deviceId / nick / my group code) belong to the group feature. Kept apart, a
 * change on either side cannot silently rewrite the other's storage.
 *
 * <p>The deviceId must survive process death, reboots and reinstalls of the app
 * data as far as possible: it is the cross-device identity of every member row,
 * mirror row and pending request, so it is written with {@code commit()} (a
 * queued {@code apply()} that dies with the process would mint a new identity on
 * the next launch and orphan everything the peers remember about us).
 */
public final class GroupIdentity {

    private static final String FILE = "tongxiao_group";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_NICK = "nick";
    private static final String KEY_GROUP_CODE = "group_code";
    /** Set once the pre-existing alarms were bulk-shared with a group. */
    private static final String KEY_SHARED_BACKFILLED = "shared_backfilled";

    private final SharedPreferences prefs;

    public GroupIdentity(@NonNull Context appContext) {
        this(appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    /** Test seam: isolated prefs per test. */
    @VisibleForTesting
    public GroupIdentity(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Generates the deviceId on first use and returns the persisted one afterwards. */
    @SuppressLint("ApplySharedPref") // deliberate: identity must be durable before use
    @NonNull
    public String deviceId() {
        String stored = prefs.getString(KEY_DEVICE_ID, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored;
        }
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit();
        return generated;
    }

    /** Nickname shown in peers' member lists; defaults to a short, unique-ish name. */
    @NonNull
    public String nick() {
        String stored = prefs.getString(KEY_NICK, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored;
        }
        String id = deviceId();
        return "舍友" + id.substring(0, Math.min(4, id.length()));
    }

    public void setNick(@Nullable String nick) {
        String trimmed = nick == null ? "" : nick.trim();
        prefs.edit().putString(KEY_NICK, trimmed).apply();
    }

    /** My 6-digit group code, or "" when this device is not in a group. */
    @NonNull
    public String groupCode() {
        String stored = prefs.getString(KEY_GROUP_CODE, "");
        return stored == null ? "" : stored.trim();
    }

    /**
     * Joins/creates a group.
     *
     * @return false when {@code code} is not exactly 6 digits (nothing is stored);
     *         pass "" to leave the group
     */
    public boolean setGroupCode(@Nullable String code) {
        String trimmed = code == null ? "" : code.trim();
        if (!trimmed.isEmpty() && !GroupProtocol.isValidGroupCode(trimmed)) {
            return false;
        }
        prefs.edit().putString(KEY_GROUP_CODE, trimmed).apply();
        return true;
    }

    /**
     * True once this device has already shared its pre-existing alarms with a group.
     *
     * <p>The bulk share exists so alarms created before the dorm feature are not
     * invisible ("nothing gets pushed"); it must happen once, not on every re-join,
     * because afterwards {@code sharedToGroup = false} is a deliberate user choice
     * that re-joining must not silently undo.
     */
    public boolean hasBackfilledSharing() {
        return prefs.getBoolean(KEY_SHARED_BACKFILLED, false);
    }

    /** Marks the one-time bulk share as done. */
    public void markSharingBackfilled() {
        prefs.edit().putBoolean(KEY_SHARED_BACKFILLED, true).apply();
    }
}
