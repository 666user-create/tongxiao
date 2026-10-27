package com.course.tongxiao.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.course.tongxiao.data.entity.GroupMember;

import java.util.List;

@Dao
public interface GroupMemberDao {

    /**
     * Insert-or-replace a row that carries ALL columns: a freshly built member
     * (all fields filled) or one read back from DB then modified. Passing a
     * partially built object would REPLACE-drop joinAt/online/lastSeenAt; use
     * {@link #updateProfile} / {@link #setGrantedMask} for field-level edits.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(GroupMember member);

    /** Leaving-group cleanup (design.md §3.3): call via GroupRepository, which also clears remote_alarms/pending_requests. */
    @Delete
    int delete(GroupMember member);

    /** Targeted profile edit (nick/groupKey), leaves joinAt/online untouched. */
    @Query("UPDATE group_members SET nick = :nick, groupKey = :groupKey WHERE deviceId = :deviceId")
    int updateProfile(String deviceId, String nick, String groupKey);

    /** Permission edits incl. the settings-page total switch (design.md §3.2): mask=0 revokes. */
    @Query("UPDATE group_members SET grantedMask = :mask WHERE deviceId = :deviceId")
    int setGrantedMask(String deviceId, int mask);

    @Query("SELECT * FROM group_members ORDER BY joinAt")
    LiveData<List<GroupMember>> observeAll();

    @Query("SELECT * FROM group_members")
    List<GroupMember> getAll();

    @Nullable
    @Query("SELECT * FROM group_members WHERE deviceId = :deviceId")
    GroupMember getByDeviceId(String deviceId);

    /** Online flag driven by heartbeat/30s timeout (Step 5.2). */
    @Query("UPDATE group_members SET online = :online, lastSeenAt = :seenAt WHERE deviceId = :deviceId")
    void setOnline(String deviceId, boolean online, long seenAt);
}
