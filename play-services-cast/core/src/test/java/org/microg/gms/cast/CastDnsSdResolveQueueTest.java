/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Deterministic callback-order tests without Android NSD or a real network. */
public class CastDnsSdResolveQueueTest {
    @Test
    public void dispatchesOnlyOnePlatformResolveAtATime() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("speaker-1", "endpoint-1");
        queue.found("speaker-2", "endpoint-2");

        CastDnsSdResolveQueue.Request<String> first = queue.takeNext();
        assertNotNull(first);
        assertEquals("endpoint-1", first.service);
        assertNull(queue.takeNext());

        assertTrue(queue.complete(first));
        CastDnsSdResolveQueue.Request<String> second = queue.takeNext();
        assertNotNull(second);
        assertEquals("endpoint-2", second.service);
        assertTrue(queue.complete(second));
        assertNull(queue.takeNext());
    }

    @Test
    public void stopAndRestartKeepOldOsResolveSlotUntilCallbackCompletes() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        long old = queue.beginDiscovery();
        queue.found("old", "endpoint-old");
        CastDnsSdResolveQueue.Request<String> pendingOld = queue.takeNext();

        queue.endDiscovery();
        long newer = queue.beginDiscovery();
        queue.found("new", "endpoint-new");

        assertFalse(queue.isDiscoveryCurrent(old));
        assertTrue(queue.isDiscoveryCurrent(newer));
        assertNull(queue.takeNext()); // Android still resolves old request
        assertFalse(queue.complete(pendingOld)); // never publish a retired result

        CastDnsSdResolveQueue.Request<String> pendingNew = queue.takeNext();
        assertNotNull(pendingNew);
        assertEquals("new", pendingNew.name);
        assertTrue(queue.complete(pendingNew));
    }

    @Test
    public void serviceLostBeforeResolveCompletesRejectsItsLateResult() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("speaker", "old");
        CastDnsSdResolveQueue.Request<String> old = queue.takeNext();
        queue.lost("speaker");

        assertFalse(queue.complete(old));
        assertNull(queue.takeNext());
        assertFalse(queue.retryAlreadyActive(old));
    }

    @Test
    public void newerSameNameAnnouncementSupersedesOldResolvedEndpoint() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("speaker", "old-ip");
        CastDnsSdResolveQueue.Request<String> old = queue.takeNext();
        queue.found("speaker", "new-ip");

        assertFalse(queue.complete(old));
        CastDnsSdResolveQueue.Request<String> updated = queue.takeNext();
        assertNotNull(updated);
        assertEquals("new-ip", updated.service);
        assertTrue(queue.complete(updated));
    }

    @Test
    public void duplicatePendingAnnouncementsUseOnlyMostRecentData() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("speaker", "first");
        queue.found("speaker", "latest");
        CastDnsSdResolveQueue.Request<String> next = queue.takeNext();
        assertEquals("latest", next.service);
        assertTrue(queue.complete(next));
        assertNull(queue.takeNext());
    }

    @Test
    public void alreadyActiveRetryIsBoundedAndCannotRestoreLostService() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("speaker", "endpoint");
        CastDnsSdResolveQueue.Request<String> attempt0 = queue.takeNext();
        assertTrue(queue.complete(attempt0));
        assertTrue(queue.retryAlreadyActive(attempt0));

        CastDnsSdResolveQueue.Request<String> attempt1 = queue.takeNext();
        assertEquals(1, attempt1.retries);
        assertTrue(queue.complete(attempt1));
        assertTrue(queue.retryAlreadyActive(attempt1));

        CastDnsSdResolveQueue.Request<String> attempt2 = queue.takeNext();
        assertEquals(2, attempt2.retries);
        assertTrue(queue.complete(attempt2));
        assertFalse(queue.retryAlreadyActive(attempt2));

        queue.found("speaker", "new");
        CastDnsSdResolveQueue.Request<String> newer = queue.takeNext();
        assertTrue(queue.complete(newer));
        queue.lost("speaker");
        assertFalse(queue.retryAlreadyActive(newer));
    }

    @Test
    public void retiredQueuedDevicesCannotAppearInLaterScans() {
        CastDnsSdResolveQueue<String> queue = new CastDnsSdResolveQueue<>();
        queue.beginDiscovery();
        queue.found("first", "one");
        queue.found("second", "two");
        queue.endDiscovery();
        queue.beginDiscovery();
        assertNull(queue.takeNext());
    }
}
