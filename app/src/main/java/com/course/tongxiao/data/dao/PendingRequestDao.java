package com.course.tongxiao.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.course.tongxiao.data.entity.PendingRequest;

import java.util.List;

@Dao
public interface PendingRequestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(PendingRequest req);

    @Query("SELECT * FROM pending_requests WHERE status = 'PENDING' ORDER BY createdAt")
    LiveData<List<PendingRequest>> observePending();

    @Query("SELECT * FROM pending_requests WHERE status = 'PENDING' ORDER BY createdAt")
    List<PendingRequest> getPending();

    @Query("UPDATE pending_requests SET status = :status WHERE reqId = :reqId")
    void setStatus(String reqId, PendingRequest.Status status);

    /** Expire sweep, runs on app start + 2h window check (Step 5.3). */
    @Query("UPDATE pending_requests SET status = 'EXPIRED' WHERE status = 'PENDING' AND expireAt < :now")
    int expireOverdue(long now);
}
