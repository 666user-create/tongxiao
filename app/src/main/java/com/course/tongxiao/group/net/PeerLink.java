package com.course.tongxiao.group.net;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.group.protocol.GroupMessage;
import com.course.tongxiao.group.protocol.MessageCodec;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * One TCP long connection to a dorm peer (design.md §4).
 *
 * <p><b>Threading:</b> exactly one reader thread per live connection. That thread
 * also drives the heartbeat, so a link needs no extra timer thread: the socket's
 * read timeout wakes it periodically, which is also the moment where it can
 * notice "this peer went silent".
 *
 * <p><b>Roles:</b>
 * <ul>
 *   <li><b>inbound</b> ({@link #accept}) — wraps a socket accepted by
 *       {@link PeerServer}; one shot, no reconnect (the dialling side retries).</li>
 *   <li><b>outbound</b> ({@link #dial}) — dials through an injected
 *       {@link Connector} and reconnects with the 2s/8s/30s backoff of
 *       design.md §4.</li>
 * </ul>
 * The connector is injected so this class never needs NSD: production passes
 * "connect to the address NSD resolved", tests pass 127.0.0.1.
 *
 * <p><b>Liveness:</b> PING every {@link Config#pingIntervalMs} (60s in
 * production). A connection dies when nothing arrived for
 * {@link Config#offlineAfterMs}. The two numbers are not independent: a 30s
 * window can never be met by a 60s heartbeat, so "30s 判离线" is only
 * self-consistent as a grace period on top of the heartbeat — production
 * therefore uses 60s + 30s = 90s (design.md §4, documented deviation).
 */
public class PeerLink implements Closeable {

    private static final String TAG = "PeerLink";

    /** Events of one connection, always delivered on the link's own reader thread. */
    public interface Listener {
        /** Streams are ready: this is the moment to send HELLO. */
        void onConnected(@NonNull PeerLink link);

        /** One complete, decoded message arrived. */
        void onMessage(@NonNull PeerLink link, @NonNull GroupMessage message);

        /** The connection ended (for dialled links it may come back via reconnect). */
        void onClosed(@NonNull PeerLink link, @NonNull String reason);
    }

    /** Opens a socket to the peer; injected so NSD stays outside this class. */
    public interface Connector {
        @NonNull
        Socket open() throws IOException;
    }

    /** Tunables; production values in {@link #defaults()}, tests inject tiny ones. */
    public static final class Config {

        public final long pingIntervalMs;
        public final long offlineAfterMs;
        private final int[] retryDelaysMs;

        public Config(long pingIntervalMs, long offlineAfterMs, int... retryDelaysMs) {
            this.pingIntervalMs = Math.max(1L, pingIntervalMs);
            // A window shorter than the heartbeat would declare healthy peers dead.
            this.offlineAfterMs = Math.max(this.pingIntervalMs, offlineAfterMs);
            this.retryDelaysMs = (retryDelaysMs == null || retryDelaysMs.length == 0)
                    ? new int[]{2_000, 8_000, 30_000} : retryDelaysMs.clone();
        }

        /** 60s heartbeat, 60s+30s grace, 2s/8s/30s reconnect backoff (design.md §4). */
        @NonNull
        public static Config defaults() {
            return new Config(60_000L, 90_000L, 2_000, 8_000, 30_000);
        }

        int retryDelay(int failedAttempts) {
            int index = Math.min(Math.max(0, failedAttempts - 1), retryDelaysMs.length - 1);
            return retryDelaysMs[index];
        }

        long tickMs() {
            return Math.max(20L, Math.min(1_000L, pingIntervalMs));
        }
    }

    /** {@code host:port} before the handshake, or the address that was dialled. */
    private final String peerKey;
    private final Socket inboundSocket;
    private final Connector connector;
    private final Listener listener;
    private final Config config;

    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile long lastInboundAt;
    private volatile long lastPingSentAt;
    private volatile String closeReason = "disconnected";
    private volatile boolean closed;
    private volatile boolean started;
    private volatile Thread thread;

    private PeerLink(@NonNull String peerKey, @Nullable Socket inboundSocket,
                     @Nullable Connector connector, @NonNull Listener listener,
                     @NonNull Config config) {
        this.peerKey = peerKey;
        this.inboundSocket = inboundSocket;
        this.connector = connector;
        this.listener = listener;
        this.config = config;
    }

    /** Wraps a socket accepted by {@link PeerServer}; no reconnect on this side. */
    @NonNull
    public static PeerLink accept(@NonNull String peerKey, @NonNull Socket acceptedSocket,
                                  @NonNull Listener listener, @NonNull Config config) {
        return new PeerLink(peerKey, acceptedSocket, null, listener, config);
    }

    /** Dials the peer and reconnects with backoff when the link drops. */
    @NonNull
    public static PeerLink dial(@NonNull String peerKey, @NonNull Connector connector,
                                @NonNull Listener listener, @NonNull Config config) {
        return new PeerLink(peerKey, null, connector, listener, config);
    }

    /** Starts the reader (or the dial/reconnect loop). Idempotent per link. */
    public void start() {
        if (started) {
            throw new IllegalStateException("PeerLink already started: " + peerKey);
        }
        started = true;
        Thread t = new Thread(this::run, "peerlink-" + peerKey);
        thread = t;
        t.start();
    }

    @NonNull
    public String peerKey() {
        return peerKey;
    }

    /** True while a connection is up and writable. */
    public boolean isConnected() {
        return output != null && !closed;
    }

    /** @return false when the link is down; the caller should queue/retry later. */
    public boolean send(@NonNull GroupMessage message) {
        OutputStream out = output;
        if (out == null) {
            return false;
        }
        byte[] frame = MessageCodec.encode(message);
        try {
            synchronized (writeLock) {
                out.write(frame);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, peerKey + ": write failed: " + e.getMessage());
            shutCurrentSocket("write failed");
            return false;
        }
    }

    /** Permanent close: also stops the reconnect loop. */
    @Override
    public void close() {
        closed = true;
        shutCurrentSocket("closed locally");
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt(); // wakes the backoff sleep
        }
    }

    // ---- internals ----

    private void run() {
        if (inboundSocket != null) {
            runConnected(inboundSocket);
            return;
        }
        int step = 0;
        while (!closed) {
            if (step > 0 && !sleepQuietly(config.retryDelay(step))) {
                return;
            }
            Socket connected = tryConnect();
            if (connected == null) {
                step++;
                continue;
            }
            long connectedAt = System.currentTimeMillis();
            runConnected(connected);
            if (closed) {
                return;
            }
            // A link that had been up long enough to heartbeat counts as a fresh
            // start (2s); one that dies immediately keeps escalating (8s/30s).
            boolean wasHealthy = System.currentTimeMillis() - connectedAt >= config.pingIntervalMs;
            step = wasHealthy ? 1 : step + 1;
        }
    }

    @Nullable
    private Socket tryConnect() {
        try {
            Socket s = connector.open();
            configure(s);
            return s;
        } catch (IOException e) {
            Log.w(TAG, peerKey + ": connect failed: " + e.getMessage());
            return null;
        }
    }

    private void runConnected(@NonNull Socket s) {
        this.socket = s;
        String reason = "disconnected";
        MessageCodec codec = new MessageCodec();
        lastInboundAt = System.currentTimeMillis();
        lastPingSentAt = System.currentTimeMillis();
        try {
            configure(s);
            s.setSoTimeout((int) config.tickMs()); // heartbeat resolution
            InputStream in = s.getInputStream();
            synchronized (writeLock) {
                output = s.getOutputStream();
            }
            listener.onConnected(this);
            byte[] buffer = new byte[1024];
            while (!closed) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (SocketTimeoutException tick) {
                    if (!heartbeatTick()) {
                        reason = closeReason;
                        return;
                    }
                    continue;
                }
                if (read < 0) {
                    reason = "peer closed"; // EOF: the other side hung up
                    return;
                }
                lastInboundAt = System.currentTimeMillis();
                List<GroupMessage> messages = codec.feed(buffer, 0, read);
                for (GroupMessage message : messages) {
                    // Listener runs on this thread so ordering is preserved; a
                    // handler may reply immediately via send().
                    listener.onMessage(this, message);
                }
                if (codec.invalidLines() > 0 || codec.oversizeLines() > 0) {
                    Log.w(TAG, peerKey + ": dropped " + codec.invalidLines()
                            + " malformed / " + codec.oversizeLines() + " oversized line(s)");
                }
            }
            reason = closeReason;
        } catch (IOException e) {
            if (!closed) {
                Log.w(TAG, peerKey + ": read failed: " + e.getMessage());
                reason = "io error: " + e.getMessage();
            }
        } finally {
            synchronized (writeLock) {
                output = null;
            }
            closeSocket(s);
            listener.onClosed(this, reason);
        }
    }

    /**
     * Runs once per read timeout: declare the peer offline when nothing arrived
     * within the window, otherwise keep the heartbeat going.
     *
     * @return false when the connection was dropped
     */
    private boolean heartbeatTick() {
        long now = System.currentTimeMillis();
        if (now - lastInboundAt >= config.offlineAfterMs) {
            Log.w(TAG, peerKey + ": heartbeat timeout after "
                    + (now - lastInboundAt) + "ms of silence");
            closeReason = "heartbeat timeout";
            shutCurrentSocket(closeReason);
            return false;
        }
        if (now - lastPingSentAt >= config.pingIntervalMs) {
            lastPingSentAt = now;
            send(GroupMessage.ping(now));
        }
        return true;
    }

    /** Closes this connection but keeps the link object alive for reconnects. */
    private void shutCurrentSocket(@NonNull String reason) {
        closeReason = reason;
        Socket s = socket;
        if (s != null) {
            closeSocket(s);
        }
    }

    private static void configure(@NonNull Socket s) {
        try {
            s.setTcpNoDelay(true);  // mute commands are tiny; do not wait for batching
            s.setKeepAlive(true);
        } catch (IOException ignored) {
            // Best effort only: a socket that rejects options still works unconfigured.
        }
    }

    private static void closeSocket(@Nullable Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing useful to do while tearing down.
        }
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return !closed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
