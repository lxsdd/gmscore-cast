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

public class CastAudioMuteControlTest {
    private static final class FakeTransport implements CastRouteVolumeRegistry.Transport {
        boolean connected = true;
        boolean muted;
        int muteCalls;

        @Override public void setVolume(float value) { }
        @Override public void setVolumeByIncrement(float increment) { }
        @Override public void setMuted(boolean muted) {
            this.muted = muted;
            muteCalls++;
        }
        @Override public boolean isConnected() { return connected; }
    }

    private static void cleanup(String route, long generation, FakeTransport transport) {
        CastRouteVolumeRegistry.unregister(route, generation, transport);
        CastRouteLifecycleRegistry.controllerReleased(generation);
        CastRouteLifecycleRegistry.forget(generation);
    }

    @Test
    public void muteRequiresCorrectActionAndBooleanWhilePreservingVolume() {
        String route = "synthetic-audio-one";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        CastRouteVolumeRegistry.updateFromReceiver(route, generation, 0.65d, false);
        assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
        CastMediaRouteController controller =
                new CastMediaRouteController(null, route, generation, true);
        try {
            assertFalse(controller.handleMuteControlRequest("not-registered", true));
            assertFalse(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, null));
            assertFalse(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, "true"));
            assertFalse(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, 1));
            assertEquals(0, transport.muteCalls);
            assertTrue(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            assertTrue(transport.muted);
            assertEquals(13, CastRouteVolumeRegistry.volumeForRoute(route));
            assertTrue(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, false));
            assertFalse(transport.muted);
            assertEquals(2, transport.muteCalls);
        } finally {
            cleanup(route, generation, transport);
        }
    }

    @Test
    public void videoRoutesAndDisconnectedOrReleasedControllersCannotMute() {
        String route = "synthetic-audio-two";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        CastRouteVolumeRegistry.register(route, generation, transport);
        CastMediaRouteController audio =
                new CastMediaRouteController(null, route, generation, true);
        CastMediaRouteController video =
                new CastMediaRouteController(null, route, generation, false);
        try {
            assertFalse(audio.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            CastRouteLifecycleRegistry.markConnecting(generation);
            assertFalse(audio.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            CastRouteLifecycleRegistry.markConnected(generation);
            assertFalse(video.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            transport.connected = false;
            assertFalse(audio.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            transport.connected = true;
            assertTrue(audio.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            CastRouteLifecycleRegistry.controllerReleased(generation);
            assertFalse(audio.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, false));
            assertEquals(1, transport.muteCalls);
        } finally {
            cleanup(route, generation, transport);
        }
    }

    @Test
    public void staleControllerCannotMuteTheReplacement() {
        String route = "synthetic-audio-three";
        long old = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport first = new FakeTransport();
        CastRouteVolumeRegistry.register(route, old, first);
        CastRouteLifecycleRegistry.markConnected(old);
        CastMediaRouteController stale =
                new CastMediaRouteController(null, route, old, true);

        long current = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport second = new FakeTransport();
        CastRouteLifecycleRegistry.markConnected(current);
        CastRouteVolumeRegistry.register(route, current, second);
        try {
            assertFalse(stale.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            CastMediaRouteController live =
                    new CastMediaRouteController(null, route, current, true);
            assertTrue(live.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_SET_MUTED, true));
            assertEquals(0, first.muteCalls);
            assertEquals(1, second.muteCalls);
        } finally {
            CastRouteVolumeRegistry.unregister(route, old, first);
            cleanup(route, current, second);
            CastRouteLifecycleRegistry.controllerReleased(old);
            CastRouteLifecycleRegistry.forget(old);
        }
    }

    @Test
    public void singlePairAndGroupShareSafeAudioMuteContract() {
        for (String route : new String[] {"synthetic-single", "synthetic-pair",
                "synthetic-group"}) {
            long gen = CastRouteLifecycleRegistry.controllerCreated(route);
            FakeTransport transport = new FakeTransport();
            CastRouteVolumeRegistry.register(route, gen, transport);
            CastRouteLifecycleRegistry.markConnected(gen);
            try {
                CastMediaRouteController controller =
                        new CastMediaRouteController(null, route, gen, true);
                assertTrue(controller.handleMuteControlRequest(
                        CastMediaRouteController.ACTION_SET_MUTED, true));
                assertEquals(1, transport.muteCalls);
            } finally {
                cleanup(route, gen, transport);
            }
        }
    }

    @Test
    public void toggleRejectsUnknownMuteStateAndRepeatedCommandsAreSafe() {
        String route = "synthetic-toggle";
        long gen = CastRouteLifecycleRegistry.controllerCreated(route);
        FakeTransport transport = new FakeTransport();
        CastRouteVolumeRegistry.register(route, gen, transport);
        CastRouteLifecycleRegistry.markConnected(gen);
        CastMediaRouteController controller =
                new CastMediaRouteController(null, route, gen, true);
        try {
            assertFalse(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_TOGGLE_MUTED, null));
            CastRouteVolumeRegistry.updateFromReceiver(route, gen, .5d, false);
            assertTrue(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_TOGGLE_MUTED, null));
            assertFalse(controller.handleMuteControlRequest(
                    CastMediaRouteController.ACTION_TOGGLE_MUTED, true));
        } finally {
            cleanup(route, gen, transport);
        }
    }
}
