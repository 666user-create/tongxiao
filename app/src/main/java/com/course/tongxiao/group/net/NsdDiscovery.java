package com.course.tongxiao.group.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.course.tongxiao.group.protocol.GroupProtocol;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thin {@link NsdManager} wrapper for {@code _tongxiao._tcp} (design.md §4).
 *
 * <p>Only discovery plumbing lives here: register our instance, browse, resolve
 * a found instance to {@code host:port}, and report it as a {@link PeerAddress}.
 * Group logic never touches NsdManager, and NSD never learns about the group.
 *
 * <p><b>Testability:</b> NSD is not available/reliable in a test environment, so
 * the class is deliberately trivial to fake:
 * <ul>
 *   <li>the {@link VisibleForTesting no-arg constructor} leaves the platform
 *       unused and behaves as "listener only";</li>
 *   <li>{@link #onPeerResolved(String, String, int)} is the single entry point a
 *       discovery result takes, so a test can inject results directly instead of
 *       waiting for mDNS.</li>
 * </ul>
 *
 * <p><b>Platform constraint:</b> {@code resolveService} may only be called for
 * one service at a time, so calls are serialized through a small queue; a
 * concurrent second resolve makes the platform throw {@code FAILURE_ALREADY_ACTIVE}.
 */
public class NsdDiscovery {

    private static final String TAG = "NsdDiscovery";

    /** Discovery results, on a platform thread. */
    public interface Listener {
        void onPeerFound(@NonNull String instanceName, @NonNull PeerAddress address);

        /** NSD is unavailable or refused to start; the group can still work by manual dial. */
        void onStartFailed(@NonNull String reason);
    }

    @Nullable
    private final NsdManager nsdManager;
    /** Only used in the @VisibleForTesting constructor; null means "platform disabled". */
    private final Deque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    @Nullable
    private NsdManager.RegistrationListener registrationListener;
    @Nullable
    private NsdManager.DiscoveryListener discoveryListener;
    @Nullable
    private Listener listener;

    /** Production constructor. */
    public NsdDiscovery(@NonNull Context appContext) {
        this.nsdManager = (NsdManager) appContext.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
    }

    /** Test seam: no platform NSD is used; results arrive via {@link #onPeerResolved}. */
    @VisibleForTesting
    public NsdDiscovery() {
        this.nsdManager = null;
    }

    /**
     * Advertises {@code instanceName} on {@code port} and starts browsing the
     * service type. Browsing also finds our own instance; callers filter it out
     * by device identity during the HELLO handshake.
     */
    public void start(@NonNull String instanceName, int port, @NonNull Listener target) {
        // Re-joining (or a second start after leaving) must not stack a second
        // registration on the platform: NsdManager answers FAILURE_ALREADY_ACTIVE
        // and the old listener would stay attached.
        stop();
        this.listener = target;
        if (nsdManager == null) {
            return; // inject-only mode (tests)
        }
        if (!GroupProtocol.isValidPort(port)) {
            target.onStartFailed("invalid port " + port);
            return;
        }
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(instanceName);
            info.setServiceType(GroupProtocol.SERVICE_TYPE);
            info.setPort(port);
            registrationListener = new RegistrationCallback();
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);

            discoveryListener = new DiscoveryCallback();
            nsdManager.discoverServices(GroupProtocol.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            // NsdManager throws unchecked when the service is unavailable.
            Log.w(TAG, "NSD start failed", e);
            target.onStartFailed(String.valueOf(e.getMessage()));
        }
    }

    /** Stops browsing and unregisters; safe to call twice. */
    public void stop() {
        NsdManager manager = nsdManager;
        if (manager == null) {
            listener = null;
            return;
        }
        if (discoveryListener != null) {
            try {
                manager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
                // Already stopped: the platform reports this as illegal argument.
            }
            discoveryListener = null;
        }
        if (registrationListener != null) {
            try {
                manager.unregisterService(registrationListener);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            registrationListener = null;
        }
        listener = null;
        synchronized (resolveQueue) {
            resolveQueue.clear();
        }
    }

    /**
     * Delivers one resolved service to the {@link Listener}.
     *
     * <p>Public because it is the seam both the platform resolve callback and the
     * tests go through: this is how a "discovery result" gets injected without a
     * real NSD stack.
     */
    public void onPeerResolved(@NonNull String instanceName, @NonNull String host, int port) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        PeerAddress address;
        try {
            address = new PeerAddress(host, port);
        } catch (IllegalArgumentException badResult) {
            Log.w(TAG, "ignoring unusable NSD result " + instanceName + " " + host + ":" + port);
            return;
        }
        target.onPeerFound(instanceName, address);
    }

    // ---- platform callbacks ----

    private final class RegistrationCallback implements NsdManager.RegistrationListener {
        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            Log.d(TAG, "registered as " + info.getServiceName());
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
            reportStartFailure("registration failed: " + errorCode);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
            Log.d(TAG, "unregistered");
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
            Log.w(TAG, "unregister failed: " + errorCode);
        }
    }

    private final class DiscoveryCallback implements NsdManager.DiscoveryListener {
        @Override
        public void onDiscoveryStarted(String serviceType) {
            Log.d(TAG, "browsing " + serviceType);
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            if (!GroupProtocol.SERVICE_TYPE.equals(info.getServiceType())) {
                return;
            }
            synchronized (resolveQueue) {
                resolveQueue.add(info);
                if (resolving) {
                    return; // the in-flight resolve will pick the queue up
                }
                resolving = true;
            }
            resolveNext();
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
            // The TCP link is the source of truth for liveness (heartbeat), so a
            // lost NSD record needs no action: a live socket keeps working.
            Log.d(TAG, "lost " + info.getServiceName());
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.d(TAG, "stopped browsing " + serviceType);
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            reportStartFailure("browse failed: " + errorCode);
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "stop browse failed: " + errorCode);
        }
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            next = resolveQueue.poll();
            if (next == null) {
                resolving = false;
                return;
            }
        }
        NsdManager manager = nsdManager;
        if (manager == null) {
            synchronized (resolveQueue) {
                resolving = false;
            }
            return;
        }
        try {
            manager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "resolve failed for " + info.getServiceName() + ": " + errorCode);
                    resolveNext();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    if (info.getHost() != null) {
                        onPeerResolved(info.getServiceName(),
                                info.getHost().getHostAddress(), info.getPort());
                    }
                    resolveNext();
                }
            });
        } catch (RuntimeException e) {
            // FAILURE_ALREADY_ACTIVE and friends: skip this one, keep the queue moving.
            Log.w(TAG, "resolve threw", e);
            resolveNext();
        }
    }

    private void reportStartFailure(@NonNull String reason) {
        Listener target = listener;
        Log.w(TAG, reason);
        if (target != null) {
            target.onStartFailed(reason);
        }
    }
}
