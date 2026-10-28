package com.course.tongxiao.group;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.group.net.NsdDiscovery;
import com.course.tongxiao.group.net.PeerAddress;
import com.course.tongxiao.group.net.PeerLink;
import com.course.tongxiao.group.net.PeerServer;
import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.GroupProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle owner of the dorm-group session (design.md §4).
 *
 * <p>Holds the TCP server, one {@link PeerLink} per peer, the HELLO handshake and
 * the online flags, and forwards protocol policy to {@link GroupCoordinator}.
 *
 * <p><b>Why NSD is not inside:</b> {@link NsdDiscovery} reports a
 * {@link PeerAddress} and the app level hands it to
 * {@link #onPeerDiscovered(String, PeerAddress)} (or registers
 * {@link #discoveryListener()}). Keeping mDNS out of this class is what makes it
 * testable end-to-end over 127.0.0.1 with real sockets and no emulator-only NSD.
 *
 * <p><b>Threading:</b> every callback arrives on a socket thread (accept, reader),
 * so all DB work here is off the main thread. Map mutations are guarded by
 * {@link #lock}.
 */
public class GroupManager implements PeerLink.Listener {

    private static final String TAG = "GroupManager";
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Optional sink for inbound traffic the UI shows (ACKs, op receipts, requests). */
    public interface Observer {
        void onPeerMessage(@NonNull String deviceId, @NonNull GroupMessage message);
    }

    /**
     * Optional sink for transport problems the page should explain instead of leaving
     * the user staring at a silently empty member list.
     */
    public interface StatusListener {
        void onDiscoveryUnavailable(@NonNull String reason);
    }

    private final TongxiaoDb db;
    private final GroupIdentity identity;
    private final GroupCoordinator coordinator;
    private final PeerLink.Config config;

    private final Object lock = new Object();
    /** {@code host:port} → link, for both dialled and accepted connections. */
    private final Map<String, PeerLink> linksByAddress = new HashMap<>();
    /** deviceId → link, known only after a validated HELLO. */
    private final Map<String, PeerLink> linksByDevice = new HashMap<>();

    private volatile PeerServer server;
    private volatile Observer observer;
    private volatile StatusListener statusListener;
    private volatile boolean closed;

    public GroupManager(@NonNull TongxiaoDb db, @NonNull SettingsStore settings,
                        @NonNull GroupIdentity identity, @NonNull AlarmGateway gateway,
                        @NonNull PeerStatsStore peerStats) {
        this(db, settings, identity, gateway, peerStats, PeerLink.Config.defaults());
    }

    /** Test seam: inject tiny heartbeat/backoff values. */
    public GroupManager(@NonNull TongxiaoDb db, @NonNull SettingsStore settings,
                        @NonNull GroupIdentity identity, @NonNull AlarmGateway gateway,
                        @NonNull PeerStatsStore peerStats, @NonNull PeerLink.Config config) {
        this.db = db;
        this.identity = identity;
        this.coordinator = new GroupCoordinator(db, settings, identity, gateway, peerStats);
        this.config = config;
    }

    @NonNull
    public GroupCoordinator coordinator() {
        return coordinator;
    }

    public void setObserver(@Nullable Observer observer) {
        this.observer = observer;
    }

    public void setStatusListener(@Nullable StatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Starts listening. The OS assigns the port, which is exactly what NSD must
     * advertise (a fixed port would collide with other apps on the dorm Wi-Fi).
     *
     * @return the listening port for NSD registration, or -1 when this device is
     *         not in a group or the socket could not be bound
     */
    public int start() {
        if (identity.groupCode().isEmpty()) {
            Log.i(TAG, "not in a group; peer server not started");
            return -1;
        }
        PeerServer current = server;
        if (current != null && !current.isClosed()) {
            return current.getPort();
        }
        synchronized (lock) {
            closed = false;
            try {
                PeerServer created = new PeerServer(0, this::onAccepted);
                created.start();
                server = created;
                return created.getPort();
            } catch (IOException e) {
                Log.e(TAG, "cannot bind peer server", e);
                return -1;
            }
        }
    }

    /** Sends BYE to every peer, then closes every link and the server. */
    public void stop() {
        List<PeerLink> links;
        synchronized (lock) {
            closed = true;
            links = new ArrayList<>(linksByAddress.values());
            linksByAddress.clear();
            linksByDevice.clear();
        }
        PeerServer current = server;
        server = null;
        if (current != null) {
            current.close();
        }
        for (PeerLink link : links) {
            link.send(GroupMessage.bye()); // graceful disconnect (design.md §4 BYE)
            link.close();
        }
    }

    public int getPort() {
        PeerServer current = server;
        return current == null ? -1 : current.getPort();
    }

    /** Adapter for {@link NsdDiscovery}: discovered peers become dialled links. */
    @NonNull
    public NsdDiscovery.Listener discoveryListener() {
        return new NsdDiscovery.Listener() {
            @Override
            public void onPeerFound(@NonNull String instanceName, @NonNull PeerAddress address) {
                onPeerDiscovered(instanceName, address);
            }

            @Override
            public void onStartFailed(@NonNull String reason) {
                Log.w(TAG, "NSD unavailable: " + reason);
                StatusListener target = statusListener;
                if (target != null) {
                    target.onDiscoveryUnavailable(reason);
                }
            }
        };
    }

    /**
     * Dials a discovered peer unless a link to that address already exists.
     * Duplicates (both sides discovering each other) are resolved after HELLO,
     * where the sender's deviceId is known.
     */
    public void onPeerDiscovered(@NonNull String instanceName, @NonNull PeerAddress address) {
        PeerLink link;
        synchronized (lock) {
            if (closed || linksByAddress.containsKey(address.key())) {
                return;
            }
            Log.d(TAG, "dialling " + instanceName + " at " + address.key());
            link = PeerLink.dial(address.key(), () -> {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address.host, address.port),
                        CONNECT_TIMEOUT_MS);
                return socket;
            }, this, config);
            linksByAddress.put(address.key(), link);
        }
        link.start();
    }

    /** @return false when that peer has no live link right now. */
    public boolean send(@NonNull String deviceId, @NonNull GroupMessage message) {
        PeerLink link;
        synchronized (lock) {
            link = linksByDevice.get(deviceId);
        }
        return link != null && link.send(message);
    }

    /**
     * Pushes my shared schedule to every member holding the visibility grant
     * (bit1, design.md §3.1/§3.2). Also sent on ring start/stop by the app so
     * peers see {@code ringing}.
     *
     * @return number of peers reached
     */
    public int broadcastSync() {
        String myGroup = identity.groupCode();
        if (myGroup.isEmpty()) {
            return 0;
        }
        GroupMessage push = coordinator.buildSyncPush();
        int sent = 0;
        for (GroupMember member : db.groupMemberDao().getAll()) {
            if ((member.grantedMask & GroupMember.PERM_SEE) == 0) {
                continue;
            }
            if (send(member.deviceId, push)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Broadcasts my leaderboard snapshot to every peer that is reachable right now.
     *
     * <p>Offline peers are <b>not</b> queued: the next broadcast carries the newest
     * snapshot anyway, which is exactly the "eventual consistency" the offline-first
     * ranking relies on. The pings/stat snapshots are small; a queue would only add
     * a stale-data failure mode.
     *
     * <p>Identity fields are filled here (never trusted from the UI), and the
     * receiver re-validates everything again.
     *
     * @param snapshot {@code medianStopMinute} / {@code days} / counts, as produced
     *                 by the stats page
     * @return number of peers the snapshot was sent to
     */
    public int broadcastStats(@NonNull JSONObject snapshot) {
        if (identity.groupCode().isEmpty()) {
            return 0; // not in a group: nothing to broadcast to
        }
        JSONObject payload;
        try {
            payload = new JSONObject(snapshot.toString());
            payload.put(GroupMessage.FIELD_STATS_DEVICE_ID, identity.deviceId());
            payload.put(GroupMessage.FIELD_NICK, identity.nick());
            payload.put(GroupMessage.FIELD_STATS_SENT_AT, System.currentTimeMillis());
        } catch (JSONException broken) {
            Log.w(TAG, "cannot encode the stats snapshot", broken);
            return 0;
        }
        GroupMessage message = GroupMessage.statsPush(payload);
        List<PeerLink> targets;
        synchronized (lock) {
            targets = new ArrayList<>(linksByDevice.values());
        }
        int sent = 0;
        for (PeerLink link : targets) {
            if (link.send(message)) {
                sent++;
            }
        }
        return sent;
    }

    // ---- PeerLink.Listener ----

    @Override
    public void onConnected(@NonNull PeerLink link) {
        // Both directions announce themselves: the peer validates our group code
        // in exactly the same way we validate theirs.
        link.send(GroupMessage.hello(identity.deviceId(), identity.nick(), identity.groupCode()));
    }

    @Override
    public void onMessage(@NonNull PeerLink link, @NonNull GroupMessage message) {
        switch (message.type()) {
            case HELLO:
                handleHello(link, message);
                return;
            case BYE:
                Log.d(TAG, "peer " + link.peerKey() + " said bye");
                link.close();
                return;
            case PING:
                // Heartbeat: liveness only, no reply needed — both sides ping.
                touch(link);
                return;
            default:
                break;
        }
        String deviceId = deviceFor(link);
        if (deviceId == null) {
            // An unidentified peer may not run commands: the granted mask lives on
            // the member row, and there is no row before a validated HELLO.
            Log.w(TAG, "ignoring " + message.type() + " from unidentified " + link.peerKey());
            return;
        }
        try {
            touch(deviceId);
            coordinator.onCommand(deviceId, message, reply -> link.send(reply));
        } catch (RuntimeException handlerFailure) {
            // A remote message must never crash the app that is (possibly) ringing:
            // the reader thread's uncaught exception would take the process down.
            Log.e(TAG, "failed to handle " + message.type() + " from " + deviceId,
                    handlerFailure);
        }
        Observer target = observer;
        if (target != null) {
            target.onPeerMessage(deviceId, message);
        }
    }

    @Override
    public void onClosed(@NonNull PeerLink link, @NonNull String reason) {
        String deviceId = null;
        synchronized (lock) {
            linksByAddress.values().remove(link);
            Iterator<Map.Entry<String, PeerLink>> it = linksByDevice.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PeerLink> entry = it.next();
                if (entry.getValue() == link) {
                    deviceId = entry.getKey();
                    it.remove();
                }
            }
        }
        Log.d(TAG, "link " + link.peerKey() + " closed: " + reason
                + (deviceId == null ? "" : " (device " + deviceId + ")"));
        if (deviceId != null) {
            try {
                db.groupMemberDao().setOnline(deviceId, false, System.currentTimeMillis());
            } catch (RuntimeException dbFailure) {
                // Closing a link must not crash the reader thread (e.g. DB already
                // shutting down); the next HELLO repairs the flag anyway.
                Log.w(TAG, "could not mark " + deviceId + " offline", dbFailure);
            }
        }
    }

    // ---- internals ----

    private void onAccepted(@NonNull Socket socket, @NonNull String peerKey) {
        PeerLink link = PeerLink.accept(peerKey, socket, this, config);
        synchronized (lock) {
            if (closed) {
                link.close();
                return;
            }
            PeerLink previous = linksByAddress.put(peerKey, link);
            if (previous != null && previous != link) {
                previous.close(); // stale registration for the same address
            }
        }
        link.start();
    }

    private void handleHello(@NonNull PeerLink link, @NonNull GroupMessage hello) {
        String failure = coordinator.onHello(hello);
        if (failure != null) {
            Log.w(TAG, "HELLO from " + link.peerKey() + " rejected: " + failure);
            link.send(GroupMessage.cmdAck(false, failure));
            link.close();
            return;
        }
        String deviceId = hello.string(GroupMessage.FIELD_DEV, "").trim();
        boolean keepNewLink;
        synchronized (lock) {
            PeerLink existing = linksByDevice.get(deviceId);
            if (existing != null && existing != link) {
                keepNewLink = false;
            } else {
                linksByDevice.put(deviceId, link);
                keepNewLink = true;
            }
        }
        if (!keepNewLink) {
            // Mesh, not a star: both sides may have dialled each other. Keep the
            // first healthy connection and drop the duplicate (design.md §4).
            Log.d(TAG, "closing duplicate link to device " + deviceId);
            link.close();
            return;
        }
        db.groupMemberDao().setOnline(deviceId, true, System.currentTimeMillis());
        // The handshake just succeeded, so the peer has a live link: push my schedule
        // now instead of waiting for the next change-detection push (its mirror would
        // otherwise stay empty, and the remote-mute entry would have nothing to show).
        try {
            broadcastSync();
        } catch (RuntimeException pushFailure) {
            // The mirror can be refreshed by the next push; the handshake itself is fine.
            Log.w(TAG, "schedule push after HELLO failed", pushFailure);
        }
    }

    @Nullable
    private String deviceFor(@NonNull PeerLink link) {
        synchronized (lock) {
            for (Map.Entry<String, PeerLink> entry : linksByDevice.entrySet()) {
                if (entry.getValue() == link) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private void touch(@NonNull PeerLink link) {
        String deviceId = deviceFor(link);
        if (deviceId != null) {
            touch(deviceId);
        }
    }

    /** Heartbeat bookkeeping: also what the mute freshness check reads (§3.2). */
    private void touch(@NonNull String deviceId) {
        db.groupMemberDao().setOnline(deviceId, true, System.currentTimeMillis());
    }
}
