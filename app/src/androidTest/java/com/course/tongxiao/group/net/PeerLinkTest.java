package com.course.tongxiao.group.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.group.TestAwait;
import com.course.tongxiao.group.protocol.GroupMessage;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 5.2 G2: TCP long connection over 127.0.0.1 — two PeerLinks talking, no
 * NSD and no emulator network tricks.
 *
 * <p>These tests exercise the real socket path (reader thread, framing, heartbeat,
 * backoff) so the handshake/command flow is proven before any UI exists.
 */
@RunWith(AndroidJUnit4.class)
public class PeerLinkTest {

    private static final String CLIENT_DEV = "client-device";
    private static final String SERVER_DEV = "server-device";
    private static final String GROUP_CODE = "123456";

    /** Fast heartbeat/backoff so a test finishes in seconds, not minutes. */
    private static PeerLink.Config fastConfig() {
        return new PeerLink.Config(60L, 400L, 40, 80, 120);
    }

    private PeerServer peerServer;
    private ServerSocket rawServer;
    private final List<PeerLink> links = new ArrayList<>();
    private final List<GroupMessage> clientInbox =
            Collections.synchronizedList(new ArrayList<>());

    @After
    public void tearDown() {
        for (PeerLink link : links) {
            link.close();
        }
        links.clear();
        if (peerServer != null) {
            peerServer.close();
            peerServer = null;
        }
        if (rawServer != null) {
            try {
                rawServer.close();
            } catch (IOException ignored) {
                // teardown
            }
            rawServer = null;
        }
    }

    /** Dialled link that announces itself and records everything it receives. */
    private PeerLink newClient(int port) {
        PeerLink client = PeerLink.dial("127.0.0.1:" + port,
                () -> new Socket("127.0.0.1", port),
                new PeerLink.Listener() {
                    @Override
                    public void onConnected(PeerLink link) {
                        link.send(GroupMessage.hello(CLIENT_DEV, "客户端", GROUP_CODE));
                    }

                    @Override
                    public void onMessage(PeerLink link, GroupMessage message) {
                        clientInbox.add(message);
                    }

                    @Override
                    public void onClosed(PeerLink link, String reason) {
                        // recorded via clientInbox only
                    }
                }, fastConfig());
        links.add(client);
        return client;
    }

    // ---- 1. HELLO handshake between two PeerLinks ----

    @Test
    public void twoPeerLinks_exchangeHelloInBothDirections() throws Exception {
        List<GroupMessage> serverInbox =
                Collections.synchronizedList(new ArrayList<>());
        CountDownLatch serverSawHello = new CountDownLatch(1);

        peerServer = openPeerServer(serverInbox, serverSawHello);
        PeerLink client = newClient(peerServer.getPort());
        client.start();

        TestAwait.until("server received the client HELLO", 5_000L,
                () -> serverSawHello.getCount() == 0);
        TestAwait.until("client received the server HELLO", 5_000L,
                () -> !clientInbox.isEmpty());

        GroupMessage fromClient = serverInbox.get(0);
        assertEquals(GroupMessage.Type.HELLO, fromClient.type());
        assertEquals(CLIENT_DEV, fromClient.string(GroupMessage.FIELD_DEV, ""));
        assertEquals(GROUP_CODE, fromClient.string(GroupMessage.FIELD_GROUP, ""));

        GroupMessage fromServer = clientInbox.get(0);
        assertEquals(GroupMessage.Type.HELLO, fromServer.type());
        assertEquals(SERVER_DEV, fromServer.string(GroupMessage.FIELD_DEV, ""));
        assertTrue(client.isConnected());
    }

    // ---- 2. CMD_MUTE round trip + CMD_ACK ----

    @Test
    public void cmdMuteRoundTrip_isAcknowledged() throws Exception {
        peerServer = new PeerServer(0, (socket, peerKey) -> {
            PeerLink server = PeerLink.accept(peerKey, socket, new PeerLink.Listener() {
                @Override
                public void onConnected(PeerLink link) {
                    link.send(GroupMessage.hello(SERVER_DEV, "执行端", GROUP_CODE));
                }

                @Override
                public void onMessage(PeerLink link, GroupMessage message) {
                    if (message.type() == GroupMessage.Type.CMD_MUTE) {
                        // The executing side decides and answers (permission checks
                        // live in GroupCoordinator; here only the wire behaviour).
                        link.send(GroupMessage.cmdAck(true, null));
                    }
                }

                @Override
                public void onClosed(PeerLink link, String reason) {
                }
            }, fastConfig());
            links.add(server);
            server.start();
        });
        peerServer.start();

        PeerLink client = newClient(peerServer.getPort());
        client.start();
        TestAwait.until("client connected", 5_000L, client::isConnected);

        assertTrue(client.send(GroupMessage.cmdMute(CLIENT_DEV, "uid-42")));

        TestAwait.until("client received CMD_ACK", 5_000L, () -> {
            synchronized (clientInbox) {
                for (GroupMessage message : clientInbox) {
                    if (message.type() == GroupMessage.Type.CMD_ACK) {
                        return true;
                    }
                }
            }
            return false;
        });

        GroupMessage ackMessage = null;
        synchronized (clientInbox) {
            for (GroupMessage message : clientInbox) {
                if (message.type() == GroupMessage.Type.CMD_ACK) {
                    ackMessage = message;
                }
            }
        }
        assertNotNull(ackMessage);
        assertTrue("mute must be acknowledged as ok", ackMessage.bool(GroupMessage.FIELD_OK, false));
    }

    // ---- 3. heartbeat: PING is emitted periodically ----

    @Test
    public void heartbeat_sendsPingToASilentPeer() throws Exception {
        rawServer = new ServerSocket();
        rawServer.setReuseAddress(true);
        rawServer.bind(new InetSocketAddress("127.0.0.1", 0));

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread acceptor = new Thread(() -> {
            try {
                Socket socket = rawServer.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // closed at teardown
            }
        }, "raw-peer-reader");
        acceptor.start();

        PeerLink client = PeerLink.dial("127.0.0.1:" + rawServer.getLocalPort(),
                () -> new Socket("127.0.0.1", rawServer.getLocalPort()),
                new PeerLink.Listener() {
                    @Override
                    public void onConnected(PeerLink link) {
                    }

                    @Override
                    public void onMessage(PeerLink link, GroupMessage message) {
                    }

                    @Override
                    public void onClosed(PeerLink link, String reason) {
                    }
                }, new PeerLink.Config(60L, 10_000L, 40, 80, 120));
        links.add(client);
        client.start();

        TestAwait.until("two heartbeats reached the silent peer", 5_000L,
                () -> countPings(lines) >= 2);
    }

    private static int countPings(List<String> lines) {
        int pings = 0;
        synchronized (lines) {
            for (String line : lines) {
                if (line.contains("\"PING\"")) {
                    pings++;
                }
            }
        }
        return pings;
    }

    // ---- 4. offline detection ----

    @Test
    public void silentPeer_isDroppedWhenNoHeartbeatArrives() throws Exception {
        rawServer = new ServerSocket();
        rawServer.setReuseAddress(true);
        rawServer.bind(new InetSocketAddress("127.0.0.1", 0));

        CountDownLatch closed = new CountDownLatch(1);
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        Thread acceptor = new Thread(() -> {
            try {
                Socket socket = rawServer.accept(); // accept, then stay silent
                TestAwait.sleep(3_000L);
                socket.close();
            } catch (IOException ignored) {
                // teardown
            }
        }, "silent-peer");
        acceptor.start();

        PeerLink client = PeerLink.dial("127.0.0.1:" + rawServer.getLocalPort(),
                () -> new Socket("127.0.0.1", rawServer.getLocalPort()),
                new PeerLink.Listener() {
                    @Override
                    public void onConnected(PeerLink link) {
                    }

                    @Override
                    public void onMessage(PeerLink link, GroupMessage message) {
                    }

                    @Override
                    public void onClosed(PeerLink link, String reason) {
                        reasons.add(reason);
                        closed.countDown();
                    }
                }, new PeerLink.Config(60L, 250L, 40, 80, 120));
        links.add(client);
        client.start();

        TestAwait.until("link gave up on the silent peer", 5_000L,
                () -> closed.getCount() == 0);
        assertTrue("expected a heartbeat timeout, got " + reasons,
                reasons.get(0).contains("heartbeat timeout"));
    }

    // ---- 5. exponential-backoff reconnect ----

    @Test
    public void outbound_reconnectsAfterThePeerDrops() throws Exception {
        rawServer = new ServerSocket();
        rawServer.setReuseAddress(true);
        rawServer.bind(new InetSocketAddress("127.0.0.1", 0));

        AtomicInteger accepts = new AtomicInteger();
        Thread acceptor = new Thread(() -> {
            while (!rawServer.isClosed()) {
                try {
                    Socket socket = rawServer.accept();
                    accepts.incrementAndGet();
                    socket.close(); // peer hangs up immediately
                } catch (IOException stopped) {
                    return;
                }
            }
        }, "flapping-peer");
        acceptor.start();

        PeerLink client = PeerLink.dial("127.0.0.1:" + rawServer.getLocalPort(),
                () -> new Socket("127.0.0.1", rawServer.getLocalPort()),
                new PeerLink.Listener() {
                    @Override
                    public void onConnected(PeerLink link) {
                    }

                    @Override
                    public void onMessage(PeerLink link, GroupMessage message) {
                    }

                    @Override
                    public void onClosed(PeerLink link, String reason) {
                    }
                }, new PeerLink.Config(50L, 10_000L, 30, 40, 60));
        links.add(client);
        client.start();

        TestAwait.until("the link reconnected at least 3 times", 6_000L,
                () -> accepts.get() >= 3);
    }

    // ---- 6. closed link ----

    @Test
    public void sendAfterClose_fails() throws Exception {
        rawServer = new ServerSocket();
        rawServer.setReuseAddress(true);
        rawServer.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread acceptor = new Thread(() -> {
            try {
                rawServer.accept();
            } catch (IOException ignored) {
                // teardown
            }
        }, "idle-peer");
        acceptor.start();

        PeerLink client = PeerLink.dial("127.0.0.1:" + rawServer.getLocalPort(),
                () -> new Socket("127.0.0.1", rawServer.getLocalPort()),
                new PeerLink.Listener() {
                    @Override
                    public void onConnected(PeerLink link) {
                    }

                    @Override
                    public void onMessage(PeerLink link, GroupMessage message) {
                    }

                    @Override
                    public void onClosed(PeerLink link, String reason) {
                    }
                }, new PeerLink.Config(1_000L, 10_000L, 40, 80, 120));
        links.add(client);
        client.start();
        TestAwait.until("client connected", 5_000L, client::isConnected);

        client.close();

        assertFalse(client.isConnected());
        assertFalse("sending on a closed link must not throw", client.send(GroupMessage.ping(1L)));
    }

    private PeerServer openPeerServer(List<GroupMessage> inbox, CountDownLatch helloLatch)
            throws IOException {
        PeerServer server = new PeerServer(0, (socket, peerKey) -> {
            PeerLink link = PeerLink.accept(peerKey, socket, new PeerLink.Listener() {
                @Override
                public void onConnected(PeerLink peerLink) {
                    peerLink.send(GroupMessage.hello(SERVER_DEV, "服务端", GROUP_CODE));
                }

                @Override
                public void onMessage(PeerLink peerLink, GroupMessage message) {
                    inbox.add(message);
                    if (message.type() == GroupMessage.Type.HELLO) {
                        helloLatch.countDown();
                    }
                }

                @Override
                public void onClosed(PeerLink peerLink, String reason) {
                }
            }, fastConfig());
            links.add(link);
            link.start();
        });
        server.start();
        return server;
    }
}
