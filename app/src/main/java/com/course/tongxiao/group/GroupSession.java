package com.course.tongxiao.group;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Access point to the <em>currently active</em> {@link GroupManager}.
 *
 * <p>The dorm page owns its GroupManager (it is created and stopped with the page's
 * lifecycle), so other screens — the stats page in particular — cannot reach it
 * through the fragment. Instead of moving group lifetime into an Application-wide
 * singleton with its own start/stop rules, the page simply publishes its instance
 * while it is alive and withdraws it when it stops.
 *
 * <p>Every entry point is safe to call at any time: when no page is active, or the
 * device is not in a group, or every peer is offline, the calls are no-ops. That is
 * exactly the "offline-first" contract — the stats page can broadcast whenever it
 * likes and nothing breaks when nobody is listening.
 */
public final class GroupSession {

    @Nullable
    private static volatile GroupManager current;

    private GroupSession() {
    }

    /** Called by {@code GroupFragment} when its services come up. */
    public static void attach(@NonNull GroupManager manager) {
        current = manager;
    }

    /** Called by {@code GroupFragment} when its services stop; only clears itself. */
    public static void detach(@NonNull GroupManager manager) {
        if (current == manager) {
            current = null;
        }
    }

    @Nullable
    public static GroupManager currentOrNull() {
        return current;
    }

    /**
     * Convenience for the stats page: fills in deviceId/nick/sentAt and broadcasts
     * my snapshot, or does nothing when no peer link is up.
     */
    public static void broadcastStats(@NonNull JSONObject snapshot) {
        GroupManager manager = current;
        if (manager == null) {
            return;
        }
        manager.broadcastStats(snapshot);
    }
}