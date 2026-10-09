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
}
