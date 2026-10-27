package com.course.tongxiao.group;

import androidx.annotation.NonNull;

import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.GroupMember;

import java.util.List;

/**
 * Leaving-group cleanup of design.md §3.3.
 *
 * <p>All references are logical foreign keys (no DB constraints), so the three
 * tables must be cleaned in one transaction or the survivors become orphans that
 * poison later reads: a leftover mirror row would keep showing a departed member's
 * schedule, and a leftover pending request would pop a confirmation for somebody
 * who is no longer in the group.
 *
 * <p>The cached leaderboard snapshots ({@link PeerStatsStore}, prefs) are removed
 * in the same call. Prefs are not part of the DB transaction, so a crash in between
 * can leave a stale ranking row — harmless and self-healing (the device only shows
 * that member while offline, and the next leave/cleanup pass removes it).
 *
 * <p>This lives in the group package because §3.3's rule is a group rule, and it
 * uses {@link TongxiaoDb#runInTransaction} directly instead of adding DAO calls to
 * {@code data/repository} (that file is owned elsewhere).
 */
public final class GroupCleanup {

    private GroupCleanup() {
    }

    /**
     * Removes one member and everything that referenced them.
     *
     * @return false when the device was not a member (nothing to do)
     */
    public static boolean removeMember(@NonNull TongxiaoDb db, @NonNull PeerStatsStore peerStats,
                                       @NonNull String deviceId) {
        GroupMember member = db.groupMemberDao().getByDeviceId(deviceId);
        if (member == null) {
            return false;
        }
        db.runInTransaction(() -> {
            db.groupMemberDao().delete(member);
            db.remoteAlarmDao().clearOwner(deviceId);
            db.pendingRequestDao().deleteBySender(deviceId);
        });
        peerStats.remove(deviceId);
        return true;
    }

    /**
     * Leaves the group entirely: every member row plus its mirror, pending requests
     * and cached leaderboard snapshot.
     *
     * @return number of members removed
     */
    public static int removeAllMembers(@NonNull TongxiaoDb db,
                                       @NonNull PeerStatsStore peerStats) {
        List<GroupMember> members = db.groupMemberDao().getAll();
        db.runInTransaction(() -> {
            for (GroupMember member : members) {
                db.groupMemberDao().delete(member);
                db.remoteAlarmDao().clearOwner(member.deviceId);
                db.pendingRequestDao().deleteBySender(member.deviceId);
            }
        });
        peerStats.clearAll();
        return members.size();
    }
}
