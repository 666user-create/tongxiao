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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(GroupMember member);

    @Delete
    void delete(GroupMember member);

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
