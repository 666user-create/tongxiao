package com.course.tongxiao.group.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.course.tongxiao.data.SettingsStore;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.group.AlarmGateway;
import com.course.tongxiao.group.GroupIdentity;
import com.course.tongxiao.group.GroupManager;
import com.course.tongxiao.group.GroupSession;
import com.course.tongxiao.group.GroupWiring;
import com.course.tongxiao.group.PeerStatsStore;
import com.course.tongxiao.group.net.NsdDiscovery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Hosts the dorm-group session outside the fragment.
 *
 * <p><b>Why a ViewModel:</b> a fragment instance does <em>not</em> survive a
 * configuration change — rotation recreates it and its {@code onCreate/onDestroy}
 * both run, which would tear the TCP links and the NSD registration down and make
 * the peer blink offline for a second. A {@link ViewModel} does survive rotation,
 * so the connection (design.md §4 "本机后台常驻") stays up while only the views are
 * rebuilt. The session is released in {@link #onCleared()}, i.e. when the hosting
 * activity really goes away.
 *
 * <p>Everything here is view-free on purpose: the fragment re-binds to the same
 * manager after every rotation and just re-attaches its UI callbacks.
 */
public class GroupSessionViewModel extends ViewModel {

    private final GroupIdentity identity;
    private final TongxiaoDb db;
    private final SettingsStore settings;
    private final PeerStatsStore peerStats;
    private final GroupManager manager;
    private final NsdDiscovery discovery;
    private final ExecutorService io;
    private boolean started;

    private GroupSessionViewModel(@NonNull Context appContext) {
        this.identity = new GroupIdentity(appContext);
        this.db = TongxiaoDb.get(appContext);
        this.settings = new SettingsStore(appContext);
        this.peerStats = new PeerStatsStore(appContext);
        AlarmGateway gateway = GroupWiring.gatewayOrNull();
        this.manager = new GroupManager(db, settings, identity,
                gateway == null ? new UnavailableGateway() : gateway, peerStats);
        this.discovery = new NsdDiscovery(appContext);
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "group-session-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    @NonNull
    public GroupIdentity identity() {
        return identity;
    }

    @NonNull
    public SettingsStore settings() {
        return settings;
    }

    @NonNull
    public TongxiaoDb db() {
        return db;
    }

    @NonNull
    public PeerStatsStore peerStats() {
        return peerStats;
    }

    @NonNull
    public GroupManager manager() {
        return manager;
    }

    @NonNull
    public NsdDiscovery discovery() {
        return discovery;
    }

    /** Runs group work off the main thread; falls back to inline once shut down. */
    public void submitIo(@NonNull Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.run();
        }
    }

    /**
     * Publishes the session and brings the transport up. Idempotent: a second
     * fragment (or the same one after rotation) just re-attaches to the live session.
     *
     * @return the listening port, or -1 when this device is not in a group
     */
    public int ensureStarted() {
        GroupSession.attach(manager);
        if (started && manager.getPort() > 0) {
            return manager.getPort();
        }
        started = true;
        if (identity.groupCode().isEmpty()) {
            return -1;
        }
        int port = manager.start();
        if (port > 0) {
            // Advertise the OS-assigned port and start browsing (design.md §4).
            discovery.start(identity.nick(), port, manager.discoveryListener());
        }
        return port;
    }

    /** Called when the group page's view goes away: the session itself keeps running. */
    public void detachUi() {
        GroupSession.detach(manager);
    }

    @Override
    protected void onCleared() {
        detachUi();
        discovery.stop();
        manager.stop();
        io.shutdown();
    }

    /** Factory so the session can be built from the application context only. */
    public static final class Factory implements ViewModelProvider.Factory {

        private final Context appContext;

        public Factory(@NonNull Context appContext) {
            this.appContext = appContext.getApplicationContext();
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked") // the requested type is this VM by contract
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(GroupSessionViewModel.class)) {
                throw new IllegalArgumentException("unsupported ViewModel " + modelClass);
            }
            return (T) new GroupSessionViewModel(appContext);
        }
    }

    /**
     * Used only when the app has not installed a real gateway: it refuses instead of
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
            return com.course.tongxiao.group.protocol.GroupProtocol.REASON_INTERNAL_ERROR;
        }

        @Nullable
        @Override
        public String apply(@NonNull com.course.tongxiao.data.entity.PendingRequest request) {
            return com.course.tongxiao.group.protocol.GroupProtocol.REASON_INTERNAL_ERROR;
        }
    }
}
