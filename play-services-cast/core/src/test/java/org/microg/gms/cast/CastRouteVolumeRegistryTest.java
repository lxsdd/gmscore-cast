/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CastRouteVolumeRegistryTest {
    private static final class FakeTransport implements CastRouteVolumeRegistry.Transport {
        boolean connected = true;
        float volume = -1;
        float increment;
        boolean muted;
        int muteCalls;
        int volumeCalls;
        boolean failMute;

        @Override public void setVolume(float volume) {
            this.volume = volume;
            volumeCalls++;
        }
        @Override public void setVolumeByIncrement(float increment) { this.increment = increment; }
        @Override public void setMuted(boolean muted) {
            if (failMute) throw new IllegalStateException("transport unavailable");
            this.muted = muted;
            muteCalls++;
        }
        @Override public boolean isConnected() { return connected; }
    }

    @Test public void absoluteRelativeBoundsAndMuteUseRegisteredTransport() {
        String route = "route-volume";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        assertTrue(CastRouteVolumeRegistry.setVolume(route, generation, 10));
        assertEquals(0.5f, transport.volume, 0.0001f);
        assertTrue(CastRouteVolumeRegistry.setVolume(route, generation, 100));
        assertEquals(1f, transport.volume, 0.0001f);
        assertTrue(CastRouteVolumeRegistry.updateVolume(route, generation, -2));
        assertEquals(-0.1f, transport.increment, 0.0001f);
        assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
        assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, true));
        assertTrue(transport.muted);
        CastRouteVolumeRegistry.unregister(route, generation, transport);
        CastRouteLifecycleRegistry.controllerReleased(generation);
        CastRouteLifecycleRegistry.forget(generation);
    }

    @Test public void staleControllerAndStaleUnregisterCannotAffectReplacement() {
        String route = "route-replacement";
        long first = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport oldTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, first, oldTransport));

        long second = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport newTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, second, newTransport));
        assertFalse(CastRouteVolumeRegistry.setVolume(route, first, 5));
        CastRouteVolumeRegistry.unregister(route, first, oldTransport);
        assertTrue(CastRouteVolumeRegistry.setVolume(route, second, 15));
        assertEquals(0.75f, newTransport.volume, 0.0001f);

        CastRouteVolumeRegistry.unregister(route, second, newTransport);
        CastRouteLifecycleRegistry.controllerReleased(first);
        CastRouteLifecycleRegistry.controllerReleased(second);
        CastRouteLifecycleRegistry.forget(first);
        CastRouteLifecycleRegistry.forget(second);
    }

    @Test public void releasedDestroyedAndDisconnectedControllersAreRejected() {
        String route = "route-release";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        CastRouteLifecycleRegistry.controllerReleased(generation);
        assertFalse(CastRouteVolumeRegistry.setVolume(route, generation, 5));
        assertFalse(CastRouteVolumeRegistry.updateVolume(route, generation, 1));
        assertFalse(CastRouteVolumeRegistry.setMuted(route, generation, true));
        CastRouteLifecycleRegistry.forget(generation);

        String disconnectedRoute = "route-disconnected";
        long disconnectedGeneration = CastRouteLifecycleRegistry.controllerCreated(disconnectedRoute);
        FakeTransport disconnected = new FakeTransport();
        disconnected.connected = false;
        assertTrue(CastRouteVolumeRegistry.register(
                disconnectedRoute, disconnectedGeneration, disconnected));
        assertFalse(CastRouteVolumeRegistry.setVolume(
                disconnectedRoute, disconnectedGeneration, 5));
        CastRouteVolumeRegistry.unregister(
                disconnectedRoute, disconnectedGeneration, disconnected);
        CastRouteLifecycleRegistry.controllerReleased(disconnectedGeneration);
        CastRouteLifecycleRegistry.forget(disconnectedGeneration);
    }

    @Test public void receiverAndCommandsPublishCurrentRouteVolume() {
        String route = "route-current-volume";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        AtomicInteger changes = new AtomicInteger();
        CastRouteVolumeRegistry.Listener listener = changedRoute -> {
            if (route.equals(changedRoute)) changes.incrementAndGet();
        };
        CastRouteVolumeRegistry.addListener(listener);
        assertEquals(10, CastRouteVolumeRegistry.volumeForRoute(route));
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.75d, false);
        assertEquals(15, CastRouteVolumeRegistry.volumeForRoute(route));
        assertEquals(1, changes.get());
        CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.75d, false);
        assertEquals(1, changes.get());
        assertTrue(CastRouteVolumeRegistry.setVolume(route, generation, 5));
        assertEquals(5, CastRouteVolumeRegistry.volumeForRoute(route));
        assertTrue(CastRouteVolumeRegistry.updateVolume(route, generation, -2));
        assertEquals(3, CastRouteVolumeRegistry.volumeForRoute(route));
        assertEquals(3, changes.get());
        CastRouteVolumeRegistry.unregister(route, generation, transport);
        CastRouteLifecycleRegistry.controllerReleased(generation);
        CastRouteLifecycleRegistry.forget(generation);
    }

    @Test public void muteTrueFalseAreNativeCommandsAndPreserveVolume() {
        String route = "route-mute";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        try {
            assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
            assertTrue(CastRouteVolumeRegistry.setVolume(route, generation, 14));
            assertEquals(14, CastRouteVolumeRegistry.volumeForRoute(route));

            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, true));
            assertTrue(transport.muted);
            assertEquals(14, CastRouteVolumeRegistry.volumeForRoute(route));
            assertFalse(CastRouteVolumeRegistry.mutedForRoute(route));

            CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.7d, true);
            assertEquals(14, CastRouteVolumeRegistry.volumeForRoute(route));
            assertTrue(CastRouteVolumeRegistry.mutedForRoute(route));

            assertTrue(CastRouteVolumeRegistry.setVolume(route, generation, 16));
            assertEquals(16, CastRouteVolumeRegistry.volumeForRoute(route));
            assertTrue(CastRouteVolumeRegistry.mutedForRoute(route));
            CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.8d, true);

            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, false));
            assertFalse(transport.muted);
            assertEquals(16, CastRouteVolumeRegistry.volumeForRoute(route));
            assertEquals(2, transport.volumeCalls);
            CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.8d, false);
            assertFalse(CastRouteVolumeRegistry.mutedForRoute(route));
            assertEquals(2, transport.muteCalls);
        } finally {
            CastRouteVolumeRegistry.unregister(route, generation, transport);
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }

    @Test public void muteTransportFailureIsContainedWithoutChangingCachedState() {
        String route = "route-mute-error";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        transport.failMute = true;
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        try {
            assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
            assertFalse(CastRouteVolumeRegistry.setMuted(route, generation, true));
            assertFalse(CastRouteVolumeRegistry.mutedForRoute(route));
            assertEquals(CastRouteVolumeRegistry.DEFAULT_ROUTE_VOLUME,
                    CastRouteVolumeRegistry.volumeForRoute(route));
            assertEquals(0, transport.muteCalls);
        } finally {
            CastRouteVolumeRegistry.unregister(route, generation, transport);
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }

    @Test public void singlePairAndGroupRoutesShareTheSameMuteContract() {
        String[] routes = {"single-speaker", "speaker-pair", "speaker-group"};
        for (String route : routes) {
            long generation = CastRouteLifecycleRegistry.controllerCreated(route);
            FakeTransport transport = new FakeTransport();
            assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
            try {
                assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
                assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, true));
                assertTrue(transport.muted);
                CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.55d, true);
                assertTrue(CastRouteVolumeRegistry.mutedForRoute(route));
                assertEquals(11, CastRouteVolumeRegistry.volumeForRoute(route));
                assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, false));
                assertFalse(transport.muted);
            } finally {
                CastRouteVolumeRegistry.unregister(route, generation, transport);
                CastRouteLifecycleRegistry.controllerReleased(generation);
                CastRouteLifecycleRegistry.forget(generation);
            }
        }
    }

    @Test public void repeatedMuteCommandsRemainIdempotentAtStateBoundary() {
        String route = "route-mute-repeat";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        try {
            assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, true));
            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, true));
            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, false));
            assertTrue(CastRouteVolumeRegistry.setMuted(route, generation, false));
            assertEquals(4, transport.muteCalls);
            assertFalse(transport.muted);
            assertEquals(CastRouteVolumeRegistry.DEFAULT_ROUTE_VOLUME,
                    CastRouteVolumeRegistry.volumeForRoute(route));
        } finally {
            CastRouteVolumeRegistry.unregister(route, generation, transport);
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }

    @Test public void muteRejectsMissingStaleDisconnectedAndReleasedSessions() {
        assertFalse(CastRouteVolumeRegistry.setMuted("missing", 1, true));

        String route = "route-mute-lifecycle";
        long first = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport firstTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, first, firstTransport));
        long second = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport secondTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, second, secondTransport));
        try {
            assertFalse(CastRouteVolumeRegistry.setMuted(route, first, true));
            assertFalse(CastRouteVolumeRegistry.setMuted(route, second, true));
            assertTrue(CastRouteLifecycleRegistry.markConnecting(second));
            assertFalse(CastRouteVolumeRegistry.setMuted(route, second, true));
            assertTrue(CastRouteLifecycleRegistry.markConnected(second));
            secondTransport.connected = false;
            assertFalse(CastRouteVolumeRegistry.setMuted(route, second, true));
            secondTransport.connected = true;
            assertTrue(CastRouteVolumeRegistry.setMuted(route, second, true));
            assertTrue(CastRouteLifecycleRegistry.markDisconnected(second));
            assertFalse(CastRouteVolumeRegistry.setMuted(route, second, false));
            assertTrue(CastRouteLifecycleRegistry.markConnected(second));
            CastRouteLifecycleRegistry.controllerReleased(second);
            assertFalse(CastRouteVolumeRegistry.setMuted(route, second, true));
            assertEquals(0, firstTransport.muteCalls);
            assertEquals(1, secondTransport.muteCalls);
        } finally {
            CastRouteVolumeRegistry.unregister(route, first, firstTransport);
            CastRouteVolumeRegistry.unregister(route, second, secondTransport);
            CastRouteLifecycleRegistry.controllerReleased(first);
            CastRouteLifecycleRegistry.controllerReleased(second);
            CastRouteLifecycleRegistry.forget(first);
            CastRouteLifecycleRegistry.forget(second);
        }
    }

    @Test public void receiverMuteStateDoesNotLeakAcrossRouteGenerations() {
        String route = "route-mute-generation";
        long first = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport firstTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, first, firstTransport));
        CastRouteVolumeRegistry.updateFromReceiver(route, first, 0.4d, true);
        assertTrue(CastRouteVolumeRegistry.mutedForRoute(route));

        long second = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport secondTransport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, second, secondTransport));
        try {
            assertFalse(CastRouteVolumeRegistry.mutedForRoute(route));
            CastRouteVolumeRegistry.updateFromReceiver(route, first, 0.4d, true);
            assertFalse(CastRouteVolumeRegistry.mutedForRoute(route));
            CastRouteVolumeRegistry.updateFromReceiver(route, second, 0.6d, false);
            assertEquals(12, CastRouteVolumeRegistry.volumeForRoute(route));
        } finally {
            CastRouteVolumeRegistry.unregister(route, first, firstTransport);
            CastRouteVolumeRegistry.unregister(route, second, secondTransport);
            CastRouteLifecycleRegistry.controllerReleased(first);
            CastRouteLifecycleRegistry.controllerReleased(second);
            CastRouteLifecycleRegistry.forget(first);
            CastRouteLifecycleRegistry.forget(second);
        }
    }
}
