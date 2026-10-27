package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Confirmation-gated remote change (challenge edit / alarm add-remove), design.md §3. */
@Entity(tableName = "pending_requests")
public class PendingRequest {

    public enum Kind { SET_CHALLENGE, ADD_ALARM, REMOVE_ALARM }
    public enum Status { PENDING, ACCEPTED, REJECTED, EXPIRED }

    @PrimaryKey
    @NonNull
    public String reqId = "";

    @NonNull
    public String fromDeviceId = "";

    @NonNull
    public Kind type = Kind.SET_CHALLENGE;

    /** Receiver-clamped payload (design.md §3.2 hard bounds applied on receipt). */
    @NonNull
    public String payloadJson = "";

    public long createdAt;
    public long expireAt;

    @NonNull
    public Status status = Status.PENDING;
}
