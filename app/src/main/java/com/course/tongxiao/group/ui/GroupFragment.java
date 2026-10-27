package com.course.tongxiao.group.ui;

import android.content.Context;
import android.os.Bundle;
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
import com.course.tongxiao.group.AlarmGateway;
import com.course.tongxiao.group.GroupCleanup;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dorm-group page (design.md §7 ④ / §5.5).
 *
 * <p>Two states only: "not in a group" (create or join with a 6-digit code) and
 * "in a group" (code + my nick, member list with permission chips, and the
 * confirmation queue for incoming setting requests).
 *
 * <p><b>Lifetime limit (documented, accepted for v1):</b> the fragment itself owns
 * the {@link GroupManager} plus {@link NsdDiscovery} — they start in
 * {@link #onStart()} and stop in {@link #onStop()}. So a member is only
 * discoverable/mutable while this page is in the foreground. A background service
 * would be the "always reachable" version; the course scope keeps it visible.
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

    private GroupIdentity identity;
    private TongxiaoDb db;
    private GroupManager manager;
    private NsdDiscovery discovery;
    private PeerStatsStore peerStats;
    private ExecutorService io;

    private final Map<String, String> nickByDevice = new HashMap<>();

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

        renderGroupState();

        // design.md §3.1: overdue confirmations are voided before the list is trusted.
        submitIo(() -> manager.coordinator().expireOverdue());
    }

    @Override
    public void onStart() {
        super.onStart();
        startServices();
    }

    @Override
    public void onStop() {
        stopServices();
        super.onStop();
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
        memberList.removeAllViews();
        nickByDevice.clear();
        boolean empty = members == null || members.isEmpty();
        memberEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (GroupMember member : members) {
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
        return row;
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
