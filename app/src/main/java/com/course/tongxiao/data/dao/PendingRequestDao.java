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

    /**
     * @return rowId, or -1 when IGNORE hit an existing reqId. -1 means either
     * (a) duplicate delivery (idempotent, fine) or (b) the same reqId already
     * sits in a terminal state (ACCEPTED/REJECTED/EXPIRED) — a re-broadcast of
     * an old request. Callers must not silently treat -1 as success; the group
     * layer (Step 5.3) queries the existing row to decide.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(PendingRequest req);

    @Query("SELECT * FROM pending_requests WHERE status = :status ORDER BY createdAt")
    LiveData<List<PendingRequest>> observeByStatus(PendingRequest.Status status);

    @Query("SELECT * FROM pending_requests WHERE status = :status ORDER BY createdAt")
    List<PendingRequest> getByStatus(PendingRequest.Status status);

    @Query("UPDATE pending_requests SET status = :status WHERE reqId = :reqId")
    int setStatus(String reqId, PendingRequest.Status status);

    /** Expire sweep, runs on app start + before UI render (Step 5.3). */
    @Query("UPDATE pending_requests SET status = :to WHERE status = :from AND expireAt < :now")
    int expireOverdue(PendingRequest.Status from, PendingRequest.Status to, long now);
}
