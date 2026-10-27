package com.course.tongxiao.data.dao;

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
     * Full-row insert-or-replace. Only call with a row that was read whole via
     * {@link #getByDeviceId}/{@link #getAll()} — REPLACE drops columns of a
     * partially built object. For field-level changes use {@link #updateProfile}
     * or {@link #setGrantedMask}.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(GroupMember member);

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

    @Query("SELECT * FROM group_members WHERE deviceId = :deviceId")
    GroupMember getByDeviceId(String deviceId);

    /** Online flag driven by heartbeat/30s timeout (Step 5.2). */
    @Query("UPDATE group_members SET online = :online, lastSeenAt = :seenAt WHERE deviceId = :deviceId")
    void setOnline(String deviceId, boolean online, long seenAt);
}
