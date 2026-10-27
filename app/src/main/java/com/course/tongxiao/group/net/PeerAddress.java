package com.course.tongxiao.group.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.group.protocol.GroupProtocol;

/** Where a discovered peer listens (design.md §4: NSD resolve result). */
public final class PeerAddress {

    @NonNull
    public final String host;
    public final int port;

    public PeerAddress(@NonNull String host, int port) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty host");
        }
        if (!GroupProtocol.isValidPort(port)) {
            throw new IllegalArgumentException("invalid port " + port);
        }
        this.host = trimmed;
        this.port = port;
    }

    /** Stable map key, e.g. {@code 192.168.1.7:41233}. */
    @NonNull
    public String key() {
        return host + ":" + port;
    }

    @NonNull
    @Override
    public String toString() {
        return key();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PeerAddress)) {
            return false;
        }
        PeerAddress that = (PeerAddress) other;
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return 31 * host.hashCode() + port;
    }
}
