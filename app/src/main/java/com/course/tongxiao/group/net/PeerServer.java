package com.course.tongxiao.group.net;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Accept loop of the dorm group (design.md §4: "持有方=本机后台常驻").
 *
 * <p>One accept thread; each accepted socket is handed to the {@link Listener}
 * together with its {@code host:port} key, and the listener owns everything that
 * happens afterwards (creating and starting a {@link PeerLink}). Keeping the
 * hand-off this small means the accept loop has no opinion about protocol state,
 * and a test can capture raw sockets without any group logic.
 *
 * <p>Port {@link #getPort()} is the OS-assigned port, which is what NSD must
 * advertise: picking a fixed port would collide with whatever else the device
 * runs (design.md §4 leaves the port to the instance).
 */
public class PeerServer implements Closeable {

    private static final String TAG = "PeerServer";
    private static final int CONNECTIONS_BACKLOG = 8;

    /** Called on the accept thread; the callee must not block for long. */
    public interface Listener {
        void onAccepted(@NonNull Socket socket, @NonNull String peerKey);
    }

    private final ServerSocket serverSocket;
    private final Listener listener;
    private volatile boolean closed;
    private volatile Thread thread;

    /**
     * @param port 0 lets the OS choose (tests and production both do this)
     * @throws IOException when the bind fails, e.g. no network permission
     */
    public PeerServer(int port, @NonNull Listener listener) throws IOException {
        this.listener = listener;
        ServerSocket socket = new ServerSocket();
        // Bind after enabling reuse: on a reconnect-heavy dorm Wi-Fi a port still
        // in TIME_WAIT must not block the restart of our listener.
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), CONNECTIONS_BACKLOG);
        this.serverSocket = socket;
    }

    /** Starts the accept loop; returns immediately. */
    public void start() {
        Thread t = new Thread(this::run, "peer-server-" + getPort());
        thread = t;
        t.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Teardown: nothing useful to report.
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void run() {
        while (!closed) {
            Socket accepted;
            try {
                accepted = serverSocket.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                // A transient accept failure must not kill the listener; a short
                // pause avoids a busy spin if the socket is in a bad state.
                Log.w(TAG, "accept failed: " + e.getMessage());
                if (!sleepQuietly()) {
                    return;
                }
                continue;
            }
            String peerKey = accepted.getInetAddress().getHostAddress()
                    + ":" + accepted.getPort();
            try {
                listener.onAccepted(accepted, peerKey);
            } catch (RuntimeException bad) {
                // One bad peer must never take the whole group down.
                Log.e(TAG, "handler failed for " + peerKey, bad);
                closeQuietly(accepted);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Teardown.
        }
    }

    private boolean sleepQuietly() {
        try {
            Thread.sleep(200L);
            return !closed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
