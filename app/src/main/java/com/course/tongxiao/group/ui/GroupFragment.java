package com.course.tongxiao.group.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.course.tongxiao.R;
import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.PendingRequest;
import com.course.tongxiao.data.entity.RemoteAlarm;
import com.course.tongxiao.group.AlarmGateway;
import com.course.tongxiao.group.GroupCleanup;
import com.course.tongxiao.group.GroupCoordinator;
import com.course.tongxiao.group.GroupIdentity;
import com.course.tongxiao.group.GroupManager;
import com.course.tongxiao.group.GroupSession;
import com.course.tongxiao.group.GroupWiring;
import com.course.tongxiao.group.PeerStatsStore;
import com.course.tongxiao.group.net.NsdDiscovery;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dorm-group page (design.md §7 ④ / §5.5).
 *
 * <p>Two states only: "not in a group" (create or join with a 6-digit code) and
 * "in a group" (code + my nick, member list with permission chips, each peer's
 * shared alarms with the one no-confirmation action "静音", and the confirmation
 * queue for incoming setting requests).
 *
 * <p><b>Session lifetime:</b> the fragment owns the {@link GroupManager} plus
 * {@link NsdDiscovery}; they start when the view is created and stop when the view
 * is destroyed. Deliberately <em>not</em> tied to onStart/onStop: when my alarm
 * rings, the full-screen stop page covers MainActivity, and a link torn down at
 * that moment would break exactly the scenario the feature exists for (a roommate
 * muting my ringing alarm). design.md §4 also describes the holder as
 * "本机后台常驻", so the connection outlives the visible page; it ends with the view
 * or the process, which keeps the course scope honest (no extra Service).
 *
 * <p>Rows are built in code instead of a RecyclerView adapter: the lists are at most
 * a few dorm members, and this keeps the whole page in one readable file (no new
 * dependency, no extra layout files).
 */
public class GroupFragment extends Fragment {

    private static final String TAG = "GroupFragment";

    /** Payload keys of the clamped request JSON (see RequestPayloads). */
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";

    /** How many of a peer's shared alarms the member row lists. */
    private static final int MAX_MIRRORED_ALARMS = 5;
    /** Change-detection period for "my schedule / ringing state changed" pushes. */
    private static final long SYNC_POLL_MS = 5_000L;
    /** CMD_ACK carries no correlation id, so only a fresh request may claim it. */
    private static final long MUTE_ACK_WINDOW_MS = 10_000L;

    private GroupIdentity identity;
    private TongxiaoDb db;
    private GroupManager manager;
    private NsdDiscovery discovery;
    private PeerStatsStore peerStats;
    private ExecutorService io;

    private final Map<String, String> nickByDevice = new HashMap<>();
    /** deviceId → the peer's schedule as last pushed to me (RemoteAlarmDao mirror). */
    private final Map<String, List<RemoteAlarm>> mirrorsByOwner = new HashMap<>();
    private final Set<String> observedOwners = new HashSet<>();
    private List<GroupMember> lastMembers = Collections.emptyList();

    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            pushScheduleIfChanged();
            syncHandler.postDelayed(this, SYNC_POLL_MS);
        }
    };
    private volatile String lastSyncFingerprint;
    private volatile String pendingMuteOwner;
    private volatile long pendingMuteAt;

    private LinearLayout joinCard;
    private LinearLayout panel;
    private LinearLayout memberList;
    private LinearLayout pendingList;
    private TextView memberEmpty;
    private TextView pendingEmpty;
    private TextView codeText;
    private TextView nickText;
    private TextView wiringWarning;
    private EditText codeInput;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "group-ui-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context appContext = requireContext().getApplicationContext();
        identity = new GroupIdentity(appContext);
        db = TongxiaoDb.get(appContext);
        peerStats = new PeerStatsStore(appContext);
        AlarmGateway gateway = GroupWiring.gatewayOrNull();
        manager = new GroupManager(db, new SettingsStore(appContext), identity,
                gateway == null ? new UnavailableGateway() : gateway, peerStats);
        discovery = new NsdDiscovery(appContext);

        joinCard = view.findViewById(R.id.group_join_card);
        panel = view.findViewById(R.id.group_panel);
        memberList = view.findViewById(R.id.group_member_list);
        pendingList = view.findViewById(R.id.group_pending_list);
        memberEmpty = view.findViewById(R.id.group_member_empty);
        pendingEmpty = view.findViewById(R.id.group_pending_empty);
        codeText = view.findViewById(R.id.group_code_text);
        nickText = view.findViewById(R.id.group_nick_text);
        wiringWarning = view.findViewById(R.id.group_wiring_warning);
        codeInput = view.findViewById(R.id.group_code_input);

        // Without an AlarmGateway the page still runs, but it must not pretend that
        // remote commands work: it says so and every remote action is refused.
        wiringWarning.setVisibility(gateway == null ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.group_create_button).setOnClickListener(v -> createGroup());
        view.findViewById(R.id.group_join_button).setOnClickListener(v -> joinGroup());
        view.findViewById(R.id.group_leave_button).setOnClickListener(v -> leaveGroup());

        db.groupMemberDao().observeAll().observe(getViewLifecycleOwner(), this::renderMembers);
        db.pendingRequestDao().observeByStatus(PendingRequest.Status.PENDING)
                .observe(getViewLifecycleOwner(), this::renderPending);

        // Remote-command feedback (CMD_ACK) for the mute button.
        manager.setObserver(this::onPeerMessage);

        renderGroupState();

        // design.md §3.1: overdue confirmations are voided before the list is trusted.
        submitIo(() -> manager.coordinator().expireOverdue());

        // The session lives with the view, not with onStart/onStop: when my alarm
        // starts ringing, the full-screen stop page covers MainActivity and would
        // stop the link exactly when a roommate needs it to mute me. design.md §4
        // even calls the holder "本机后台常驻", so the link outlives the visible page
        // and only ends with the view/process.
        startServices();
        syncHandler.removeCallbacks(syncTick);
        syncHandler.post(syncTick);
    }

    @Override
    public void onDestroyView() {
        syncHandler.removeCallbacks(syncTick);
        stopServices();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        ExecutorService executor = io;
        io = null;
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    // ---- group membership ----

    private void createGroup() {
        String code = generateGroupCode();
        if (!identity.setGroupCode(code)) {
            toast(R.string.group_code_invalid);
            return;
        }
        renderGroupState();
        startServices();
        toastText(getString(R.string.group_created, code));
    }

    private void joinGroup() {
        String entered = codeInput.getText() == null ? "" : codeInput.getText().toString().trim();
        if (!GroupProtocol.isValidGroupCode(entered) || !identity.setGroupCode(entered)) {
            toast(R.string.group_code_invalid);
            return;
        }
        codeInput.getText().clear();
        renderGroupState();
        startServices();
        toastText(getString(R.string.group_joined, entered));
    }

    private void leaveGroup() {
        identity.setGroupCode("");
        stopServices();
        submitIo(() -> {
            // §3.3 transactional cleanup + the cached leaderboard snapshots.
            int removed = GroupCleanup.removeAllMembers(db, peerStats);
            Log.i(TAG, "left the group, removed " + removed + " member row(s)");
        });
        renderGroupState();
        toast(R.string.group_left);
    }

    /** 6 random digits; the code only has to be unguessable within one dorm. */
    private static String generateGroupCode() {
        return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    // ---- services ----

    private void startServices() {
        renderGroupState();
        // Publish the live manager so other screens (stats ranking) can broadcast
        // their snapshot without owning the group lifecycle (see GroupSession).
        GroupSession.attach(manager);
        if (identity.groupCode().isEmpty()) {
            return;
        }
        submitIo(() -> {
            int port = manager.start();
            if (port <= 0) {
                toast(R.string.group_service_failed);
                return;
            }
            // Advertise the OS-assigned port and start browsing (design.md §4).
            discovery.start(identity.nick(), port, manager.discoveryListener());
        });
    }

    private void stopServices() {
        discovery.stop();
        GroupSession.detach(manager);
        submitIo(manager::stop);
    }

    /**
     * Pushes my schedule when anything in it changed — in particular when an alarm
     * starts or stops ringing, which is what makes the peer's 静音 button appear.
     *
     * <p>Why a comparison instead of a listener: the ringing flag is read live from
     * the ring service by {@code buildSyncPush()}, and the group package must not
     * hook into the service's internals. Polling the built message is the smallest
     * reliable trigger, and it only sends when the content actually differs.
     */
    private void pushScheduleIfChanged() {
        if (identity == null || identity.groupCode().isEmpty()) {
            return;
        }
        submitIo(() -> {
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

    /**
     * Inbound feedback for outgoing commands.
     *
     * <p>{@code CMD_ACK} has no request id (design.md §4), so mute answers are
     * correlated by the {@code what} tag ("MUTE:<uid>"). An untagged ACK is never
     * treated as a success — it is most likely a schedule-push acknowledgement — but
     * a failure is still surfaced when a mute is in flight, so a denial is not lost
     * when talking to a peer build without tags.
     */
    private void onPeerMessage(@NonNull String deviceId, @NonNull GroupMessage message) {
        if (message.type() != GroupMessage.Type.CMD_ACK) {
            return;
        }
        String owner = pendingMuteOwner;
        if (owner == null || !owner.equals(deviceId)) {
            return;
        }
        boolean fresh = System.currentTimeMillis() - pendingMuteAt <= MUTE_ACK_WINDOW_MS;
        String what = message.string(GroupMessage.FIELD_WHAT, "");
        boolean taggedMuteAck = what.startsWith(GroupCoordinator.ACK_WHAT_MUTE);
        boolean ok = message.bool(GroupMessage.FIELD_OK, false);
        if (!taggedMuteAck && ok) {
            return; // e.g. "your schedule arrived" — not an answer to the mute
        }
        if (!fresh) {
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

    // ---- rendering ----

    private void renderGroupState() {
        if (joinCard == null) {
            return;
        }
        String code = identity.groupCode();
        boolean joined = !code.isEmpty();
        joinCard.setVisibility(joined ? View.GONE : View.VISIBLE);
        panel.setVisibility(joined ? View.VISIBLE : View.GONE);
        if (!joined) {
            return;
        }
        codeText.setText(getString(R.string.group_code_label, code));
        nickText.setText(getString(R.string.group_nick_label, identity.nick()));
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

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        if (member.grantedMask == 0) {
            chips.addView(buildChip(getString(R.string.group_member_no_perm), context));
        }
        if ((member.grantedMask & GroupMember.PERM_MUTE) != 0) {
            chips.addView(buildChip(getString(R.string.perm_mute), context));
        }
        if ((member.grantedMask & GroupMember.PERM_SEE) != 0) {
            chips.addView(buildChip(getString(R.string.perm_see), context));
        }
        if ((member.grantedMask & GroupMember.PERM_SET) != 0) {
            chips.addView(buildChip(getString(R.string.perm_set), context));
        }
        LinearLayout.LayoutParams chipRowParams = wrapParams();
        chipRowParams.topMargin = dp(4);
        row.addView(chips, chipRowParams);

        List<RemoteAlarm> shared = mirrorsByOwner.get(member.deviceId);
        if (shared != null && !shared.isEmpty()) {
            row.addView(buildSharedSchedule(member, shared));
        }
        return row;
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
     * i.e. the member whose alarm this is. Using my own deviceId here would be
     * rejected by that member: the command must target an alarm on the receiver.
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

    private View buildChip(@NonNull String text, @NonNull Context context) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextColor(ContextCompat.getColor(context, R.color.green_on_container));
        chip.setTextSize(11);
        chip.setBackgroundColor(ContextCompat.getColor(context, R.color.green_container));
        chip.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams params = wrapParams();
        params.setMarginEnd(dp(6));
        chip.setLayoutParams(params);
        return chip;
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

    // ---- decisions ----

    private void decide(@NonNull String reqId, boolean accept) {
        submitIo(() -> {
            PendingRequest resolved = accept
                    ? manager.coordinator().accept(reqId)
                    : manager.coordinator().reject(reqId);
            if (resolved == null) {
                toast(R.string.group_request_failed);
                return;
            }
            // Tell the requesting peer the outcome (design.md §4 SET_RESPONSE).
            manager.send(resolved.fromDeviceId, GroupMessage.setResponse(reqId, accept));
            toast(accept ? R.string.group_request_accepted : R.string.group_request_rejected);
        });
    }

    // ---- plumbing ----

    /** Runs group work off the main thread; falls back to inline once shut down. */
    private void submitIo(@NonNull Runnable task) {
        ExecutorService executor = io;
        if (executor == null || executor.isShutdown()) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.run();
        }
    }

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

    /**
     * Used only until the app installs a real gateway: it refuses instead of
     * pretending, so a mis-wired build cannot silently "succeed" a remote mute.
     */
    private static final class UnavailableGateway implements AlarmGateway {
        @Override
        public boolean isRinging(@NonNull String alarmUid) {
            return false;
        }

        @Nullable
        @Override
        public String mute(@NonNull String alarmUid, @NonNull String fromDeviceId) {
            Log.w(TAG, "no AlarmGateway wired; refusing MUTE for " + alarmUid);
            return GroupProtocol.REASON_INTERNAL_ERROR;
        }

        @Nullable
        @Override
        public String apply(@NonNull PendingRequest request) {
            Log.w(TAG, "no AlarmGateway wired; refusing request " + request.reqId);
            return GroupProtocol.REASON_INTERNAL_ERROR;
        }
    }
}
