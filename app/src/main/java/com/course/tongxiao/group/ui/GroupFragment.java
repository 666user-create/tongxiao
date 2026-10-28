package com.course.tongxiao.group.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.course.tongxiao.R;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.Alarm;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.group.GroupCleanup;
import com.course.tongxiao.group.GroupCoordinator;
import com.course.tongxiao.group.GroupIdentity;
import com.course.tongxiao.group.GroupManager;
import com.course.tongxiao.group.GroupWiring;
import com.course.tongxiao.group.PeerStatsStore;
import com.course.tongxiao.group.net.NsdDiscovery;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dorm-group page (design.md §7 ④ / §5.5).
 *
 * <p>Two states only: "not in a group" (create or join with a 6-digit code) and
 * "in a group" (code + my nick, per-member permission chips, each peer's shared
 * alarms with the no-confirmation "静音" action, the setting-request confirmation
 * queue, and an in-memory operation log).
 *
 * <p><b>Session lifetime:</b> the transport lives in {@link GroupSessionViewModel},
 * which survives configuration changes — rotating the screen rebuilds the views but
 * keeps the TCP links and the NSD registration (design.md §4 "本机后台常驻"). The
 * session is released when the hosting activity really goes away.
 *
 * <p>Rows are built in code instead of a RecyclerView adapter: the lists hold at
 * most a few dorm members, and this keeps the page in one readable file (no new
 * dependency, no extra layout files).
 */
public class GroupFragment extends Fragment {

    private static final String TAG = "GroupFragment";

    /** Payload keys of the clamped request JSON (see RequestPayloads). */
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_LABEL = "label";
    private static final String KEY_REPEAT_MODE = "repeatMode";
    private static final String KEY_SNOOZE_MIN = "snoozeMin";
    private static final String KEY_VIBRATE = "vibrate";

    /** How many of a peer's shared alarms the member row lists. */
    private static final int MAX_MIRRORED_ALARMS = 5;
    /** Change-detection period for "my schedule / ringing state changed" pushes. */
    private static final long SYNC_POLL_MS = 5_000L;
    /** Mute ACKs are only accepted for a freshly sent request. */
    private static final long MUTE_ACK_WINDOW_MS = 10_000L;
    private static final int MAX_OP_LOG_LINES = 20;

    private GroupSessionViewModel session;
    private GroupIdentity identity;
    private TongxiaoDb db;
    private GroupManager manager;
    private NsdDiscovery discovery;
    private PeerStatsStore peerStats;

    private final Map<String, String> nickByDevice = new HashMap<>();
    /** deviceId → the peer's schedule as last pushed to me (RemoteAlarmDao mirror). */
    private final Map<String, List<RemoteAlarm>> mirrorsByOwner = new HashMap<>();
    private final Set<String> observedOwners = new HashSet<>();
    private List<GroupMember> lastMembers = Collections.emptyList();
    /** Newest first; memory only (an operation log is not worth a table). */
    private final Deque<String> opLog = new ArrayDeque<>();

    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            // Never poll while the page is not the one in front of the user (P2-7).
            if (!isAdded() || isRemoving() || isHidden() || !isResumed()) {
                return; // onResume() re-arms the tick
            }
            pushScheduleIfChanged();
            syncHandler.postDelayed(this, SYNC_POLL_MS);
        }
    };
    private volatile String lastSyncFingerprint;
    private volatile String pendingMuteOwner;
    private volatile long pendingMuteAt;
    @Nullable
    private volatile String nsdFailureReason;

    private LinearLayout joinCard;
    private LinearLayout panel;
    private LinearLayout memberList;
    private LinearLayout pendingList;
    private TextView memberEmpty;
    private TextView pendingEmpty;
    private TextView codeText;
    private TextView nickText;
    private TextView wiringWarning;
    private TextView masterOffBanner;
    private TextView nsdBanner;
    private TextView shareSummary;
    private TextView opLogView;
    private TextView opLogEmpty;
    private EditText codeInput;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        session = new ViewModelProvider(this, new GroupSessionViewModel.Factory(requireContext()))
                .get(GroupSessionViewModel.class);
        identity = session.identity();
        db = session.db();
        manager = session.manager();
        discovery = session.discovery();
        peerStats = session.peerStats();

        joinCard = view.findViewById(R.id.group_join_card);
        panel = view.findViewById(R.id.group_panel);
        memberList = view.findViewById(R.id.group_member_list);
        pendingList = view.findViewById(R.id.group_pending_list);
        memberEmpty = view.findViewById(R.id.group_member_empty);
        pendingEmpty = view.findViewById(R.id.group_pending_empty);
        codeText = view.findViewById(R.id.group_code_text);
        nickText = view.findViewById(R.id.group_nick_text);
        wiringWarning = view.findViewById(R.id.group_wiring_warning);
        masterOffBanner = view.findViewById(R.id.group_master_off_banner);
        nsdBanner = view.findViewById(R.id.group_nsd_banner);
        shareSummary = view.findViewById(R.id.group_share_summary);
        opLogView = view.findViewById(R.id.group_op_log);
        opLogEmpty = view.findViewById(R.id.group_op_log_empty);
        codeInput = view.findViewById(R.id.group_code_input);

        // Without an AlarmGateway the page still runs, but it must not pretend that
        // remote commands work: it says so and every remote action is refused.
        wiringWarning.setVisibility(GroupWiring.gatewayOrNull() == null
                ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.group_create_button).setOnClickListener(v -> createGroup());
        view.findViewById(R.id.group_join_button).setOnClickListener(v -> joinGroup());
        view.findViewById(R.id.group_leave_button).setOnClickListener(v -> leaveGroup());
        view.findViewById(R.id.group_copy_code_button).setOnClickListener(v -> copyGroupCode());
        view.findViewById(R.id.group_nick_edit_button).setOnClickListener(v -> editNick());

        db.groupMemberDao().observeAll().observe(getViewLifecycleOwner(), this::renderMembers);
        db.pendingRequestDao().observeByStatus(PendingRequest.Status.PENDING)
                .observe(getViewLifecycleOwner(), this::renderPending);
        db.alarmDao().observeAll().observe(getViewLifecycleOwner(), this::renderShareSummary);

        // Remote-command feedback (ACKs) and the operation log.
        manager.setObserver(this::onPeerMessage);
        manager.setStatusListener(reason -> {
            nsdFailureReason = reason;
            appendOp(getString(R.string.group_nsd_unavailable, reason));
            // The op log is easy to miss, and "no roommates yet" looks like an empty
            // group rather than a broken LAN discovery: say it out loud once.
            if (isAdded() && getView() != null) {
                com.google.android.material.snackbar.Snackbar.make(requireView(),
                        getString(R.string.group_service_failed),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            }
        });

        renderGroupState();
        renderOpLog();

        // design.md §3.1: overdue confirmations are voided before the list is trusted.
        session.submitIo(() -> manager.coordinator().expireOverdue());

        // Idempotent: after a rotation the same session is simply re-attached.
        session.ensureStarted();
        startPolling();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The settings-page master switch may have changed while we were away.
        renderGroupState();
        startPolling();
    }

    @Override
    public void onPause() {
        stopPolling();
        super.onPause();
    }

    /**
     * The tab host swaps fragments with show/hide (MainActivity pattern), so a
     * hidden→visible transition does NOT call {@link #onResume}. Without this the
     * master-switch banner stayed stale: turning 允许宿舍互助 off in 设置 and coming
     * back to 宿舍 still showed the "舍友可远程静音" hint until the app restarted.
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            renderGroupState();
            renderOpLog();
        }
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        if (manager != null) {
            manager.setObserver(null);
            manager.setStatusListener(null);
        }
        if (session != null) {
            session.detachUi();
        }
        joinCard = null;
        panel = null;
        memberList = null;
        pendingList = null;
        memberEmpty = null;
        pendingEmpty = null;
        codeText = null;
        nickText = null;
        wiringWarning = null;
        masterOffBanner = null;
        nsdBanner = null;
        shareSummary = null;
        opLogView = null;
        codeInput = null;
        super.onDestroyView();
    }

    // ---- group membership ----

    private void createGroup() {
        String code = generateGroupCode();
        if (!identity.setGroupCode(code)) {
            toast(R.string.group_code_invalid);
            return;
        }
        afterJoiningGroup();
        toastText(getString(R.string.group_created, code));
    }

    private void joinGroup() {
        String entered = codeInput.getText() == null ? "" : codeInput.getText().toString().trim();
        if (!GroupProtocol.isValidGroupCode(entered) || !identity.setGroupCode(entered)) {
            toast(R.string.group_code_invalid);
            return;
        }
        codeInput.getText().clear();
        afterJoiningGroup();
        toastText(getString(R.string.group_joined, entered));
    }

    /**
     * Turns group membership into working traffic: existing alarms become shared
     * (they were created before the group existed, so nothing would ever be pushed),
     * the transport starts, and my schedule goes out immediately.
     */
    private void afterJoiningGroup() {
        renderGroupState();
        session.submitIo(() -> {
            // One-time bulk share: alarms that predate the group would otherwise never
            // be pushed. Re-joining must NOT redo it — after the first time,
            // sharedToGroup=false is a deliberate choice made on the edit screen.
            if (!identity.hasBackfilledSharing()) {
                int rows = db.alarmDao().setSharedToAll(true);
                identity.markSharingBackfilled();
                Log.i(TAG, "shared " + rows + " existing alarm(s) with the group (one-time)");
            }
            manager.broadcastSync();
        });
        session.ensureStarted();
    }

    private void leaveGroup() {
        identity.setGroupCode("");
        session.submitIo(() -> {
            // §3.3 transactional cleanup + the cached leaderboard snapshots.
            int removed = GroupCleanup.removeAllMembers(db, peerStats);
            Log.i(TAG, "left the group, removed " + removed + " member row(s)");
            manager.stop();
        });
        renderGroupState();
        toast(R.string.group_left);
    }

    /** 6 random digits; the code only has to be unguessable within one dorm. */
    private static String generateGroupCode() {
        return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    private void copyGroupCode() {
        String code = identity.groupCode();
        if (code.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.group_code_copy), code));
        toast(R.string.group_code_copied);
    }

    private void editNick() {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setHint(R.string.group_nick_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(identity.nick());
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(input);

        new AlertDialog.Builder(context)
                .setTitle(R.string.group_nick_edit_title)
                .setView(box)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) -> {
                    identity.setNick(input.getText().toString());
                    renderGroupState();
                    toast(R.string.group_nick_saved);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---- transport helpers ----

    /**
     * Pushes my schedule when anything in it changed — in particular when an alarm
     * starts or stops ringing, which is what makes the peer's 静音 button appear.
     *
     * <p>The ringing flag itself is read live from the ring service by
     * {@code buildSyncPush()}; what was missing was a trigger, so the page compares
     * the built message and only sends when the content really differs.
     */
    private void pushScheduleIfChanged() {
        if (identity == null || identity.groupCode().isEmpty()) {
            return;
        }
        session.submitIo(() -> {
            if (identity.groupCode().isEmpty()) {
                return;
            }
            String fingerprint;
            try {
                fingerprint = manager.coordinator().buildSyncPush().toJson();
            } catch (RuntimeException broken) {
                Log.w(TAG, "cannot build the schedule push", broken);
                return;
            }
            if (fingerprint.equals(lastSyncFingerprint)) {
                return; // nothing changed: stay quiet
            }
            lastSyncFingerprint = fingerprint;
            manager.broadcastSync();
        });
    }

    private void startPolling() {
        syncHandler.removeCallbacks(syncTick);
        syncHandler.post(syncTick);
    }

    private void stopPolling() {
        syncHandler.removeCallbacks(syncTick);
    }

    // ---- inbound messages ----

    private void onPeerMessage(@NonNull String deviceId, @NonNull GroupMessage message) {
        switch (message.type()) {
            case CMD_ACK:
                handleAck(deviceId, message);
                break;
            case NOTIFY_OP:
                if (message.string(GroupMessage.FIELD_WHAT, "").startsWith("MUTE")) {
                    appendOp(getString(R.string.group_op_mute_received));
                }
                break;
            case SET_RESPONSE:
                appendOp(getString(message.bool(GroupMessage.FIELD_ACCEPT, false)
                        ? R.string.group_op_set_response_accept
                        : R.string.group_op_set_response_reject));
                break;
            default:
                break;
        }
    }

    /**
     * ACKs are attributed by their {@code what} tag ("MUTE:<uid>" / "SET:<reqId>"):
     * schedule pushes also produce an untagged ok ACK, and a mute must never be
     * "confirmed" by one of those.
     */
    private void handleAck(@NonNull String deviceId, @NonNull GroupMessage message) {
        boolean ok = message.bool(GroupMessage.FIELD_OK, false);
        String what = message.string(GroupMessage.FIELD_WHAT, "");
        if (what.startsWith(GroupCoordinator.ACK_WHAT_SET)) {
            appendOp(ok ? getString(R.string.group_op_set_queued)
                    : getString(R.string.group_op_set_denied,
                    setFailureText(message.string(GroupMessage.FIELD_REASON, ""))));
            return;
        }
        if (!what.startsWith(GroupCoordinator.ACK_WHAT_MUTE)) {
            return; // e.g. "your schedule arrived": not an answer to a command
        }
        String owner = pendingMuteOwner;
        if (owner == null || !owner.equals(deviceId)) {
            return;
        }
        if (System.currentTimeMillis() - pendingMuteAt > MUTE_ACK_WINDOW_MS) {
            pendingMuteOwner = null;
            return;
        }
        pendingMuteOwner = null;
        if (ok) {
            toast(R.string.group_mute_ok);
            return;
        }
        toastText(muteFailureText(message.string(GroupMessage.FIELD_REASON, "")));
    }

    @NonNull
    private String muteFailureText(@NonNull String reason) {
        if (GroupProtocol.REASON_PERMISSION_DENIED.equals(reason)) {
            return getString(R.string.group_mute_denied);
        }
        if (GroupProtocol.REASON_MASTER_SWITCH_OFF.equals(reason)) {
            return getString(R.string.group_mute_blocked);
        }
        if (GroupProtocol.REASON_ALARM_NOT_FOUND.equals(reason)) {
            return getString(R.string.group_mute_not_found);
        }
        if (GroupProtocol.REASON_NOT_RINGING.equals(reason)) {
            return getString(R.string.group_mute_not_ringing);
        }
        if (GroupProtocol.REASON_STALE_LINK.equals(reason)) {
            return getString(R.string.group_mute_stale);
        }
        return getString(R.string.group_mute_failed, reason);
    }

    @NonNull
    private String setFailureText(@Nullable String reason) {
        String code = reason == null ? "" : reason;
        if (GroupProtocol.REASON_PERMISSION_DENIED.equals(code)) {
            return getString(R.string.group_set_denied);
        }
        if (GroupProtocol.REASON_MASTER_SWITCH_OFF.equals(code)) {
            return getString(R.string.group_set_blocked);
        }
        return code.isEmpty() ? getString(R.string.group_request_failed) : code;
    }

    // ---- rendering ----

    private void renderGroupState() {
        if (joinCard == null) {
            return;
        }
        String code = identity.groupCode();
        boolean joined = !code.isEmpty();
        joinCard.setVisibility(joined ? View.GONE : View.VISIBLE);
        panel.setVisibility(joined ? View.VISIBLE : View.GONE);
        renderBanners();
        if (!joined) {
            return;
        }
        codeText.setText(getString(R.string.group_code_label, code));
        nickText.setText(getString(R.string.group_nick_label, identity.nick()));
    }

    /** Master-switch and discovery warnings: silent failures are un-demoable. */
    private void renderBanners() {
        if (masterOffBanner == null) {
            return;
        }
        boolean masterOn = session.settings().isGroupMasterSwitchOn();
        masterOffBanner.setVisibility(masterOn ? View.GONE : View.VISIBLE);
        String nsdReason = nsdFailureReason;
        if (nsdReason == null) {
            nsdBanner.setVisibility(View.GONE);
        } else {
            nsdBanner.setText(getString(R.string.group_nsd_unavailable, nsdReason));
            nsdBanner.setVisibility(View.VISIBLE);
        }
    }

    private void renderShareSummary(@Nullable List<Alarm> alarms) {
        if (shareSummary == null) {
            return;
        }
        int shared = 0;
        if (alarms != null) {
            for (Alarm alarm : alarms) {
                if (alarm.sharedToGroup) {
                    shared++;
                }
            }
        }
        shareSummary.setText(getString(R.string.group_share_summary, shared));
    }

    private void renderMembers(@Nullable List<GroupMember> members) {
        if (memberList == null) {
            return;
        }
        lastMembers = members == null ? Collections.emptyList() : new ArrayList<>(members);
        observeMirrors(lastMembers);
        renderMemberRows();
    }

    /**
     * One LiveData per member: the schedule that peer pushed to me. Observers are
     * added once per device and live with the view, so a member coming back does not
     * stack duplicates.
     */
    private void observeMirrors(@NonNull List<GroupMember> members) {
        for (GroupMember member : members) {
            if (!observedOwners.add(member.deviceId)) {
                continue;
            }
            db.remoteAlarmDao().observeOwner(member.deviceId)
                    .observe(getViewLifecycleOwner(), rows -> {
                        mirrorsByOwner.put(member.deviceId,
                                rows == null ? Collections.emptyList() : rows);
                        renderMemberRows();
                    });
        }
    }

    private void renderMemberRows() {
        if (memberList == null) {
            return;
        }
        memberList.removeAllViews();
        nickByDevice.clear();
        memberEmpty.setVisibility(lastMembers.isEmpty() ? View.VISIBLE : View.GONE);
        for (GroupMember member : lastMembers) {
            nickByDevice.put(member.deviceId, member.nick);
            memberList.addView(buildMemberRow(member));
        }
    }

    private void renderPending(@Nullable List<PendingRequest> requests) {
        if (pendingList == null) {
            return;
        }
        pendingList.removeAllViews();
        boolean empty = requests == null || requests.isEmpty();
        pendingEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (PendingRequest request : requests) {
            pendingList.addView(buildPendingRow(request));
        }
    }

    private void renderOpLog() {
        if (opLogView == null) {
            return;
        }
        boolean empty = opLog.isEmpty();
        if (opLogEmpty != null) {
            opLogEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (empty) {
            opLogView.setVisibility(View.GONE);
            return;
        }
        opLogView.setText(String.join("\n", new ArrayList<>(opLog)));
        opLogView.setVisibility(View.VISIBLE);
    }

    /** Newest first, capped; safe to call from any thread. */
    private void appendOp(@NonNull String line) {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(() -> {
            if (opLogView == null) {
                return;
            }
            opLog.addFirst(line);
            while (opLog.size() > MAX_OP_LOG_LINES) {
                opLog.removeLast();
            }
            renderOpLog();
        });
    }

    private View buildMemberRow(@NonNull GroupMember member) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(context);
        dot.setText(R.string.group_member_online_dot);
        dot.setTextColor(ContextCompat.getColor(context,
                member.online ? R.color.green_primary : R.color.muted));
        dot.setTextSize(11);
        titleRow.addView(dot);

        TextView name = new TextView(context);
        name.setText(member.nick);
        name.setTextColor(ContextCompat.getColor(context, R.color.ink));
        name.setTextSize(15);
        LinearLayout.LayoutParams nameParams = wrapParams();
        nameParams.setMarginStart(dp(6));
        titleRow.addView(name, nameParams);
        row.addView(titleRow);

        // Chips are the grant editor: tapping one toggles that permission bit
        // (design.md §3.1 grantedMask) and the peer is re-synced right away.
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(buildGrantChip(member, GroupMember.PERM_MUTE, R.string.perm_mute, context));
        chips.addView(buildGrantChip(member, GroupMember.PERM_SEE, R.string.perm_see, context));
        chips.addView(buildGrantChip(member, GroupMember.PERM_SET, R.string.perm_set, context));
        LinearLayout.LayoutParams chipRowParams = wrapParams();
        chipRowParams.topMargin = dp(4);
        row.addView(chips, chipRowParams);

        List<RemoteAlarm> shared = mirrorsByOwner.get(member.deviceId);
        if (shared != null && !shared.isEmpty()) {
            row.addView(buildSharedSchedule(member, shared));
        }

        MaterialButton applyButton = new MaterialButton(context);
        applyButton.setText(R.string.group_request_apply_button);
        applyButton.setOnClickListener(v -> showAddAlarmDialog(member));
        LinearLayout.LayoutParams applyParams = wrapParams();
        applyParams.topMargin = dp(6);
        row.addView(applyButton, applyParams);
        return row;
    }

    private View buildGrantChip(@NonNull GroupMember member, int permissionBit,
                                int labelRes, @NonNull Context context) {
        boolean granted = (member.grantedMask & permissionBit) != 0;
        TextView chip = new TextView(context);
        chip.setText(labelRes);
        chip.setTextColor(ContextCompat.getColor(context,
                granted ? R.color.green_on_container : R.color.muted));
        chip.setTextSize(11);
        chip.setBackgroundColor(ContextCompat.getColor(context,
                granted ? R.color.green_container : R.color.line));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setOnClickListener(v -> toggleGrant(member, permissionBit, labelRes));
        LinearLayout.LayoutParams params = wrapParams();
        params.setMarginEnd(dp(6));
        chip.setLayoutParams(params);
        return chip;
    }

    private void toggleGrant(@NonNull GroupMember member, int permissionBit, int labelRes) {
        int newMask = member.grantedMask ^ permissionBit;
        boolean granted = (newMask & permissionBit) != 0;
        session.submitIo(() -> {
            db.groupMemberDao().setGrantedMask(member.deviceId, newMask);
            if (permissionBit == GroupMember.PERM_SEE) {
                // Visibility changed: refresh what that peer may see right away.
                manager.broadcastSync();
            }
        });
        toastText(getString(granted ? R.string.group_grant_on : R.string.group_grant_off,
                getString(labelRes)));
    }

    /** The peer's shared schedule, plus the one no-confirmation action: mute. */
    private View buildSharedSchedule(@NonNull GroupMember member,
                                     @NonNull List<RemoteAlarm> shared) {
        Context context = requireContext();
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);

        int shown = 0;
        for (RemoteAlarm alarm : shared) {
            if (shown++ >= MAX_MIRRORED_ALARMS) {
                break;
            }
            block.addView(buildMirrorRow(member, alarm, context));
        }
        LinearLayout.LayoutParams blockParams = wrapParams();
        blockParams.topMargin = dp(6);
        block.setLayoutParams(blockParams);
        return block;
    }

    private View buildMirrorRow(@NonNull GroupMember member, @NonNull RemoteAlarm alarm,
                                @NonNull Context context) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(2), 0, dp(2));

        TextView time = new TextView(context);
        time.setText(alarm.timeText);
        time.setTextColor(ContextCompat.getColor(context, R.color.ink));
        time.setTextSize(13);
        line.addView(time);

        TextView label = new TextView(context);
        label.setText(alarm.alarmLabel == null || alarm.alarmLabel.trim().isEmpty()
                ? getString(R.string.alarm_default_label) : alarm.alarmLabel);
        label.setTextColor(ContextCompat.getColor(context, R.color.muted));
        label.setTextSize(12);
        LinearLayout.LayoutParams labelParams = wrapParams();
        labelParams.setMarginStart(dp(6));
        line.addView(label, labelParams);

        if (alarm.ringingNow) {
            TextView ringing = new TextView(context);
            ringing.setText(R.string.group_member_ringing);
            ringing.setTextColor(ContextCompat.getColor(context, R.color.green_primary));
            ringing.setTextSize(12);
            ringing.setTypeface(ringing.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams ringParams = wrapParams();
            ringParams.setMarginStart(dp(8));
            line.addView(ringing, ringParams);

            MaterialButton mute = new MaterialButton(context);
            mute.setText(R.string.group_mute);
            mute.setOnClickListener(v -> requestMute(member, alarm));
            LinearLayout.LayoutParams muteParams = wrapParams();
            muteParams.setMarginStart(dp(8));
            line.addView(mute, muteParams);
        }
        return line;
    }

    /**
     * Sends CMD_MUTE for a peer's ringing alarm.
     *
     * <p>{@code ownerDev} is the alarm's owner — the receiving side (design.md §4),
     * i.e. the member whose alarm this is; using my own deviceId would be rejected
     * by that member.
     */
    private void requestMute(@NonNull GroupMember member, @NonNull RemoteAlarm alarm) {
        if (!manager.send(member.deviceId, GroupMessage.cmdMute(member.deviceId,
                alarm.srcAlarmUid))) {
            toast(R.string.group_mute_no_link);
            return;
        }
        pendingMuteOwner = member.deviceId;
        pendingMuteAt = System.currentTimeMillis();
    }

    private View buildPendingRow(@NonNull PendingRequest request) {
        Context context = requireContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(ContextCompat.getColor(context, R.color.card));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        TextView title = new TextView(context);
        title.setText(describe(request));
        title.setTextColor(ContextCompat.getColor(context, R.color.ink));
        title.setTextSize(14);
        card.addView(title);

        String nick = nickByDevice.get(request.fromDeviceId);
        TextView from = new TextView(context);
        from.setText(getString(R.string.group_request_from,
                nick == null ? request.fromDeviceId : nick));
        from.setTextColor(ContextCompat.getColor(context, R.color.muted));
        from.setTextSize(11);
        LinearLayout.LayoutParams fromParams = wrapParams();
        fromParams.topMargin = dp(2);
        card.addView(from, fromParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        MaterialButton accept = new MaterialButton(context);
        accept.setText(R.string.group_accept);
        accept.setOnClickListener(v -> decide(request.reqId, true));
        actions.addView(accept, wrapParams());

        MaterialButton reject = new MaterialButton(context);
        reject.setText(R.string.group_reject);
        reject.setOnClickListener(v -> decide(request.reqId, false));
        LinearLayout.LayoutParams rejectParams = wrapParams();
        rejectParams.setMarginStart(dp(8));
        actions.addView(reject, rejectParams);

        LinearLayout.LayoutParams actionParams = wrapParams();
        actionParams.topMargin = dp(6);
        card.addView(actions, actionParams);
        return card;
    }

    /** One line that tells the owner what they are about to allow. */
    @NonNull
    private String describe(@NonNull PendingRequest request) {
        try {
            JSONObject payload = new JSONObject(request.payloadJson);
            switch (request.type) {
                case ADD_ALARM:
                    return getString(R.string.group_request_add_alarm,
                            String.format(Locale.US, "%02d:%02d",
                                    payload.optInt(KEY_HOUR, 0), payload.optInt(KEY_MINUTE, 0)));
                case REMOVE_ALARM:
                    return getString(R.string.group_request_remove_alarm);
                case SET_CHALLENGE:
                default:
                    return getString(R.string.group_request_set_challenge);
            }
        } catch (JSONException broken) {
            Log.w(TAG, "unreadable pending payload " + request.reqId, broken);
            return getString(R.string.group_request_set_challenge);
        }
    }

    // ---- setting requests ----

    /** Minimal in-place editor for "please set an alarm for me" (ADD_ALARM). */
    private void showAddAlarmDialog(@NonNull GroupMember member) {
        Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        EditText timeInput = new EditText(context);
        timeInput.setHint(R.string.group_request_time_hint);
        timeInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        EditText labelInput = new EditText(context);
        labelInput.setHint(R.string.group_request_label_hint);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(timeInput);
        box.addView(labelInput);

        new AlertDialog.Builder(context)
                .setTitle(R.string.group_request_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) ->
                        sendAddAlarmRequest(member, timeInput.getText().toString(),
                                labelInput.getText().toString()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void sendAddAlarmRequest(@NonNull GroupMember member, @NonNull String timeText,
                                     @NonNull String label) {
        int[] time = parseTime(timeText);
        if (time == null) {
            toast(R.string.group_request_invalid_time);
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put(KEY_HOUR, time[0]);
            payload.put(KEY_MINUTE, time[1]);
            payload.put(KEY_LABEL, label.trim());
            payload.put(KEY_REPEAT_MODE, Alarm.RepeatMode.DAILY.name());
            payload.put(KEY_SNOOZE_MIN, 10);
            payload.put(KEY_VIBRATE, true);
        } catch (JSONException impossible) {
            toast(R.string.group_request_invalid_time);
            return;
        }
        if (!manager.send(member.deviceId, GroupMessage.setRequest(
                UUID.randomUUID().toString(), "ADD_ALARM", payload))) {
            toast(R.string.group_request_no_link);
            return;
        }
        toast(R.string.group_request_sent);
    }

    /** @return {hour, minute} for "HH:mm", or null when it is not a valid clock time. */
    @Nullable
    private static int[] parseTime(@NonNull String text) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return new int[]{hour, minute};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void decide(@NonNull String reqId, boolean accept) {
        session.submitIo(() -> {
            PendingRequest resolved = accept
                    ? manager.coordinator().accept(reqId)
                    : manager.coordinator().reject(reqId);
            if (resolved == null) {
                if (!accept) {
                    toast(R.string.group_request_failed);
                    return;
                }
                String reason = manager.coordinator().lastDecisionFailure();
                toastText(reason == null
                        ? getString(R.string.group_request_failed)
                        : getString(R.string.group_request_accept_failed,
                        setFailureText(reason)));
                appendOp(getString(R.string.group_op_accept_failed,
                        reason == null ? "" : setFailureText(reason)));
                return;
            }
            // Tell the requesting peer the outcome (design.md §4 SET_RESPONSE).
            manager.send(resolved.fromDeviceId, GroupMessage.setResponse(reqId, accept));
            toast(accept ? R.string.group_request_accepted : R.string.group_request_rejected);
            appendOp(getString(accept ? R.string.group_op_accept_ok : R.string.group_op_rejected));
        });
    }

    // ---- plumbing ----

    private void toast(int stringRes) {
        toastText(getString(stringRes));
    }

    private void toastText(@NonNull String text) {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getContext() != null) {
                Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @NonNull
    private static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
