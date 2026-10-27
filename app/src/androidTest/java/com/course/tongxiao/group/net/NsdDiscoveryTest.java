package com.course.tongxiao.group.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * NSD is replaced by an injected discovery result (design.md §4): the group must
 * be testable without mDNS, which is unreliable on a test device and impossible on
 * a single emulator.
 */
@RunWith(AndroidJUnit4.class)
public class NsdDiscoveryTest {

    private final List<PeerAddress> found = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private NsdDiscovery.Listener listener() {
        return new NsdDiscovery.Listener() {
            @Override
            public void onPeerFound(String instanceName, PeerAddress address) {
                found.add(address);
            }

            @Override
            public void onStartFailed(String reason) {
                failures.add(reason);
            }
        };
    }

    @Test
    public void injectedDiscoveryResult_reachesTheListener() {
        // No-arg constructor = platform disabled, results are injected by hand.
        NsdDiscovery discovery = new NsdDiscovery();
        discovery.start("小明", 41000, listener());

        discovery.onPeerResolved("小明-phone", "192.168.1.7", 41233);

        assertEquals(1, found.size());
        assertEquals("192.168.1.7", found.get(0).host);
        assertEquals(41233, found.get(0).port);
        assertTrue(failures.isEmpty());
    }

    @Test
    public void unusableDiscoveryResult_isIgnored() {
        NsdDiscovery discovery = new NsdDiscovery();
        discovery.start("小明", 41000, listener());

        discovery.onPeerResolved("bad-port", "192.168.1.7", 0);
        discovery.onPeerResolved("bad-host", "", 41000);

        assertTrue("nothing usable was reported", found.isEmpty());
        assertTrue("a broken NSD record is not a start failure", failures.isEmpty());
    }

    @Test
    public void resultsBeforeStart_areDroppedWithoutCrashing() {
        NsdDiscovery discovery = new NsdDiscovery();

        discovery.onPeerResolved("early", "192.168.1.7", 41000);

        assertTrue(found.isEmpty());
    }

    @Test
    public void stop_clearsTheListener() {
        NsdDiscovery discovery = new NsdDiscovery();
        discovery.start("小明", 41000, listener());

        discovery.stop();
        discovery.onPeerResolved("late", "192.168.1.7", 41000);
        discovery.stop(); // second stop must not throw

        assertTrue(found.isEmpty());
    }
}
