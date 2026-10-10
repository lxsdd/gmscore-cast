/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CastRouteDiscoveryStateTest {
    @Test
    public void routeSnapshotIsDetachedFromDiscoveryAndCategoryMutations() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        state.rememberIfAbsent("id-1", "service-1", "first");
        state.addCategory("category-1");

        CastRouteDiscoveryState.Snapshot<String> before = state.snapshot();
        state.forgetService("service-1");
        state.addCategory("category-2");
        state.rememberIfAbsent("id-2", "service-2", "second");

        assertEquals(1, before.devices.size());
        assertEquals("first", before.devices.get(0));
        assertEquals(1, before.categories.size());
        assertEquals("category-1", before.categories.get(0));
        assertEquals("second", state.getDevice("id-2"));
        assertNull(state.getDevice("id-1"));
    }

    @Test
    public void duplicateDiscoveryPreservesExistingRouteAndServiceMapping() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        state.rememberIfAbsent("route", "service-original", "first");
        state.rememberIfAbsent("route", "service-new", "second");

        assertEquals("first", state.getDevice("route"));
        state.forgetService("service-new");
        assertEquals("first", state.getDevice("route"));
        state.forgetService("service-original");
        assertNull(state.getDevice("route"));
    }

    @Test
    public void backgroundMutationsCannotInvalidateConcurrentRouteSnapshots() throws Exception {
        final CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    String id = "route-" + i;
                    String service = "service-" + i;
                    state.rememberIfAbsent(id, service, id);
                    state.addCategory("cat-" + i);
                    state.forgetService(service);
                }
                return null;
            });
            Future<?> reader1 = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    CastRouteDiscoveryState.Snapshot<String> snapshot = state.snapshot();
                    for (String id : snapshot.devices) assertFalse(id.isEmpty());
                    for (String category : snapshot.categories) assertFalse(category.isEmpty());
                }
                return null;
            });
            Future<?> reader2 = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    CastRouteDiscoveryState.Snapshot<String> snapshot = state.snapshot();
                    for (String id : snapshot.devices) assertFalse(id.isEmpty());
                    for (String category : snapshot.categories) assertFalse(category.isEmpty());
                }
                return null;
            });
            start.countDown();
            writer.get(30, TimeUnit.SECONDS);
            reader1.get(30, TimeUnit.SECONDS);
            reader2.get(30, TimeUnit.SECONDS);
            assertTrue(state.snapshot().devices.isEmpty());
            assertEquals(2000, state.snapshot().categories.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void rediscoveryUpdatesCapabilitiesWithoutChangingRouteId() {
        CastRouteDiscoveryState<Integer> state = new CastRouteDiscoveryState<>();
        assertTrue(state.putDevice("route-id", "service-a", 4, false));
        assertEquals(Integer.valueOf(4), state.getDevice("route-id"));

        assertFalse(state.putDevice("route-id", "service-a", 5, false));
        assertEquals(Integer.valueOf(4), state.getDevice("route-id"));

        assertTrue(state.putDevice("route-id", "service-a", 5, true));
        assertEquals(Integer.valueOf(5), state.getDevice("route-id"));
        assertEquals(1, state.snapshot().devices.size());
    }

    @Test
    public void serviceAliasesDoNotRemoveAStillAdvertisedReceiver() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        state.putDevice("route", "service-old", "endpoint", false);
        state.putDevice("route", "service-new", "endpoint", false);

        state.forgetService("service-old");
        assertEquals("endpoint", state.getDevice("route"));
        state.forgetService("service-new");
        assertNull(state.getDevice("route"));
    }

    @Test
    public void serviceNameReusedByNewRouteRemovesOrphanedOldRoute() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        state.putDevice("old", "name", "first", false);
        state.putDevice("new", "name", "second", false);
        assertNull(state.getDevice("old"));
        assertEquals("second", state.getDevice("new"));
    }

    @Test
    public void chooserReopenReplacesInsteadOfAccumulatingCategories() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        assertTrue(state.replaceCategories(java.util.Arrays.asList("video", "audio", "audio")));
        assertEquals(java.util.Arrays.asList("video", "audio"), state.snapshot().categories);
        assertFalse(state.replaceCategories(java.util.Arrays.asList("video", "audio")));
        assertTrue(state.replaceCategories(java.util.Collections.singletonList("audio")));
        assertEquals(java.util.Collections.singletonList("audio"), state.snapshot().categories);
        assertTrue(state.replaceCategories(null));
        assertTrue(state.snapshot().categories.isEmpty());
    }

    @Test
    public void passiveDiscoveryRequestPreservesSelectorCategories() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        java.util.List<String> castCategories = java.util.Arrays.asList(
                "urn:x-cast:app", "urn:x-cast:audio");

        assertTrue(state.replaceRequestCategories(true, castCategories));
        // The same valid request remains authoritative when activeScan is false.
        assertFalse(state.replaceRequestCategories(true, castCategories));
        assertEquals(castCategories, state.snapshot().categories);
        assertTrue(state.replaceRequestCategories(false, castCategories));
        assertTrue(state.snapshot().categories.isEmpty());
        // A new valid passive request is allowed to republish its selector.
        assertTrue(state.replaceRequestCategories(true, castCategories));
        assertEquals(castCategories, state.snapshot().categories);
    }

    @Test
    public void selectedRouteSurvivesDnsSdLossAndDisappearsAfterDeselect() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        Object selectedController = new Object();
        state.putDevice("receiver", "service", "endpoint", true);
        assertTrue(state.selectRoute("receiver", selectedController));
        state.forgetService("service");
        assertEquals("endpoint", state.getDevice("receiver"));
        assertTrue(state.isSelected("receiver"));
        assertTrue(state.releaseRoute("receiver", selectedController));
        assertNull(state.getDevice("receiver"));
    }

    @Test
    public void creatingControllerAloneDoesNotRetainUnselectedLostRoute() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        state.putDevice("receiver", "service", "endpoint", true);
        state.forgetService("service");
        assertNull(state.getDevice("receiver"));
        assertFalse(state.selectRoute("receiver", new Object()));
    }

    @Test
    public void staleControllerCannotReleaseReplacementControllerSelection() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        Object first = new Object(), second = new Object();
        state.putDevice("receiver", "service", "endpoint", true);
        assertTrue(state.selectRoute("receiver", first));
        assertFalse(state.selectRoute("receiver", first));
        assertTrue(state.selectRoute("receiver", second));
        state.forgetService("service");
        assertTrue(state.releaseRoute("receiver", first));
        assertEquals("endpoint", state.getDevice("receiver"));
        assertFalse(state.releaseRoute("receiver", first));
        assertTrue(state.releaseRoute("receiver", second));
        assertNull(state.getDevice("receiver"));
    }

    @Test
    public void rediscoveryWhileSelectedPreservesRouteAfterControllerReleases() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        Object controller = new Object();
        state.putDevice("receiver", "old", "old-endpoint", true);
        state.selectRoute("receiver", controller);
        state.forgetService("old");
        state.putDevice("receiver", "new", "new-endpoint", true);
        assertEquals("new-endpoint", state.getDevice("receiver"));
        assertTrue(state.releaseRoute("receiver", controller));
        assertEquals("new-endpoint", state.getDevice("receiver"));
        state.forgetService("new");
        assertNull(state.getDevice("receiver"));
    }

    @Test
    public void reassignedServiceDoesNotEvictCurrentlySelectedFormerRoute() {
        CastRouteDiscoveryState<String> state = new CastRouteDiscoveryState<>();
        Object controller = new Object();
        state.putDevice("old-id", "service", "first", true);
        state.selectRoute("old-id", controller);
        state.putDevice("new-id", "service", "second", true);
        assertEquals("first", state.getDevice("old-id"));
        assertEquals("second", state.getDevice("new-id"));
        state.releaseRoute("old-id", controller);
        assertNull(state.getDevice("old-id"));
    }
}
