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

public class CastRouteConnectionStateTest {
    @Test
    public void lifecyclePublishesOnlyMeaningfulTransitions() {
        String route = "ordinary-cast-route";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        AtomicInteger changes = new AtomicInteger();
        CastRouteLifecycleRegistry.Listener listener = (changedRoute, changedGeneration,
                                                         connectionState) -> {
            if (route.equals(changedRoute)) changes.incrementAndGet();
        };
        CastRouteLifecycleRegistry.addListener(listener);
        try {
            assertEquals(CastRouteLifecycleRegistry.CONNECTION_STATE_DISCONNECTED,
                    CastRouteLifecycleRegistry.snapshotForRoute(route).connectionState);
            assertTrue(CastRouteLifecycleRegistry.markConnecting(generation));
            assertFalse(CastRouteLifecycleRegistry.markConnecting(generation));
            assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
            assertFalse(CastRouteLifecycleRegistry.markConnected(generation));
            assertTrue(CastRouteLifecycleRegistry.markDisconnected(generation));
            assertFalse(CastRouteLifecycleRegistry.markDisconnected(generation));
            assertEquals(3, changes.get());
        } finally {
            CastRouteLifecycleRegistry.removeListener(listener);
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }

    @Test
    public void staleControllerCannotPromoteOrDisconnectReplacement() {
        String route = "replacement-route";
        long oldGeneration = CastRouteLifecycleRegistry.controllerCreated(route);
        assertTrue(CastRouteLifecycleRegistry.markConnecting(oldGeneration));
        long currentGeneration = CastRouteLifecycleRegistry.controllerCreated(route);
        try {
            assertFalse(CastRouteLifecycleRegistry.markConnected(oldGeneration));
            assertTrue(CastRouteLifecycleRegistry.markConnecting(currentGeneration));
            assertTrue(CastRouteLifecycleRegistry.markConnected(currentGeneration));
            assertFalse(CastRouteLifecycleRegistry.markDisconnected(oldGeneration));
            assertEquals(CastRouteLifecycleRegistry.CONNECTION_STATE_CONNECTED,
                    CastRouteLifecycleRegistry.snapshotForRoute(route).connectionState);
            CastRouteLifecycleRegistry.controllerReleased(currentGeneration);
            assertEquals(CastRouteLifecycleRegistry.CONNECTION_STATE_DISCONNECTED,
                    CastRouteLifecycleRegistry.snapshotForRoute(route).connectionState);
        } finally {
            CastRouteLifecycleRegistry.controllerReleased(oldGeneration);
            CastRouteLifecycleRegistry.controllerReleased(currentGeneration);
            CastRouteLifecycleRegistry.forget(oldGeneration);
            CastRouteLifecycleRegistry.forget(currentGeneration);
        }
    }

    @Test
    public void routeLossAndReleaseAlwaysReturnCurrentRouteToDisconnected() {
        String route = "lost-route";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        try {
            assertTrue(CastRouteLifecycleRegistry.markConnecting(generation));
            assertTrue(CastRouteLifecycleRegistry.markConnected(generation));
            CastRouteLifecycleRegistry.routeLost(route);
            assertEquals(CastRouteLifecycleRegistry.CONNECTION_STATE_DISCONNECTED,
                    CastRouteLifecycleRegistry.snapshotForRoute(route).connectionState);

            assertTrue(CastRouteLifecycleRegistry.markConnecting(generation));
            CastRouteLifecycleRegistry.controllerReleased(generation);
            assertEquals(CastRouteLifecycleRegistry.CONNECTION_STATE_DISCONNECTED,
                    CastRouteLifecycleRegistry.snapshotForRoute(route).connectionState);
            assertFalse(CastRouteLifecycleRegistry.markConnected(generation));
        } finally {
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }
}
