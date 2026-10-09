/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CastMediaRouteVolumeThreadTest {
    @Test public void queuedCommandCannotReachReleasedRoute() throws Exception {
        String blockingRoute = "volume-worker-blocker";
        String staleRoute = "volume-worker-stale";
        long blockingGeneration = CastRouteLifecycleRegistry.controllerCreated(blockingRoute);
        long staleGeneration = CastRouteLifecycleRegistry.controllerCreated(staleRoute);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicInteger staleCalls = new AtomicInteger();
        CastRouteVolumeRegistry.Transport blocker = new CastRouteVolumeRegistry.Transport() {
            public void setVolume(float value) throws Exception {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker timed out");
            }
            public void setVolumeByIncrement(float delta) { drained.countDown(); }
            public void setMuted(boolean muted) { }
            public boolean isConnected() { return true; }
        };
        CastRouteVolumeRegistry.Transport stale = new CastRouteVolumeRegistry.Transport() {
            public void setVolume(float value) { staleCalls.incrementAndGet(); }
            public void setVolumeByIncrement(float delta) { staleCalls.incrementAndGet(); }
            public void setMuted(boolean muted) { }
            public boolean isConnected() { return true; }
        };
        assertTrue(CastRouteVolumeRegistry.register(blockingRoute, blockingGeneration, blocker));
        assertTrue(CastRouteVolumeRegistry.register(staleRoute, staleGeneration, stale));
        try {
            CastMediaRouteController first = new CastMediaRouteController(null, blockingRoute, blockingGeneration, true);
            CastMediaRouteController second = new CastMediaRouteController(null, staleRoute, staleGeneration, true);
            first.onSetVolume(10);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second.onSetVolume(10);
            second.onUpdateVolume(-1);
            CastRouteLifecycleRegistry.controllerReleased(staleGeneration);
            first.onUpdateVolume(-1);
            release.countDown();
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertEquals(0, staleCalls.get());
        } finally {
            release.countDown();
            CastRouteVolumeRegistry.unregister(blockingRoute, blockingGeneration, blocker);
            CastRouteVolumeRegistry.unregister(staleRoute, staleGeneration, stale);
            CastRouteLifecycleRegistry.controllerReleased(blockingGeneration);
            CastRouteLifecycleRegistry.controllerReleased(staleGeneration);
            CastRouteLifecycleRegistry.forget(blockingGeneration);
            CastRouteLifecycleRegistry.forget(staleGeneration);
        }
    }

    @Test public void volumeCallbacksRunOffCallerThreadInOrder() throws Exception {
        String route = "volume-worker-test";
        long generation = CastRouteLifecycleRegistry.controllerCreated(route);
        Thread caller = Thread.currentThread();
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Thread> worker = new AtomicReference<>();
        StringBuffer calls = new StringBuffer();
        CastRouteVolumeRegistry.Transport transport = new CastRouteVolumeRegistry.Transport() {
            public void setVolume(float volume) {
                worker.set(Thread.currentThread());
                calls.append("absolute;");
                done.countDown();
            }
            public void setVolumeByIncrement(float delta) {
                calls.append("relative;");
                done.countDown();
            }
            public void setMuted(boolean muted) { }
            public boolean isConnected() { return true; }
        };
        assertTrue(CastRouteVolumeRegistry.register(route, generation, transport));
        try {
            CastMediaRouteController controller = new CastMediaRouteController(null, route, generation, true);
            controller.onSetVolume(10);
            controller.onUpdateVolume(-1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNotSame(caller, worker.get());
            assertEquals("absolute;relative;", calls.toString());
        } finally {
            CastRouteVolumeRegistry.unregister(route, generation, transport);
            CastRouteLifecycleRegistry.controllerReleased(generation);
            CastRouteLifecycleRegistry.forget(generation);
        }
    }
}
