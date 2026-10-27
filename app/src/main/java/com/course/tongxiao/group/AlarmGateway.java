package com.course.tongxiao.group;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.entity.PendingRequest;

/**
 * Seam between the group protocol and the alarm side of the app.
 *
 * <p>The group package must not touch {@code RingService} / {@code AlarmScheduler}
 * directly: the "mute the ringing alarm" capability and "apply an accepted
 * request" step belong to the alarm layer, so they are injected. That keeps the
 * protocol policy unit-testable with a fake gateway, and keeps the group code
 * from growing a second copy of the ringing state machine.
 *
 * <p><b>Contract:</b> implementations are called on a background (network reader)
 * thread, must not throw, and must be idempotent-ish: a duplicate mute of an alarm
 * that already stopped should report {@code ALARM_NOT_FOUND}/{@code NOT_RINGING}
 * rather than crashing.
 */
public interface AlarmGateway {

    /** @return true while {@code alarmUid} is the alarm currently ringing on this device. */
    boolean isRinging(@NonNull String alarmUid);

    /**
     * Stops the alarm that is ringing right now (design.md §3.2: mute needs no
     * confirmation) and records the operation with source {@code remote:<deviceId>}.
     *
     * <p>Implementation recipe (alarm layer): resolve {@code alarmUid} via
     * {@code AlarmDao.getByUid}; unknown → {@code ALARM_NOT_FOUND}; not the ringing
     * one → {@code NOT_RINGING}; otherwise stop the ring service and call
     * {@code RingActions.onStopped(alarm, startedAt, "remote:" + fromDeviceId)} so
     * {@code alarm_events.meta} keeps the trace (design.md §3.2 "操作留痕").
     *
     * @return null on success, otherwise a {@code GroupProtocol} reason code to ACK
     */
    @Nullable
    String mute(@NonNull String alarmUid, @NonNull String fromDeviceId);

    /**
     * Applies an owner-confirmed request (design.md §5.5 "同意才落库重排"):
     * insert/update/remove the alarm and re-arm it via {@code AlarmScheduler}.
     * The payload was already clamped on receipt, so it is local-safe.
     *
     * @return null on success, otherwise a {@code GroupProtocol} reason code
     */
    @Nullable
    String apply(@NonNull PendingRequest request);
}
