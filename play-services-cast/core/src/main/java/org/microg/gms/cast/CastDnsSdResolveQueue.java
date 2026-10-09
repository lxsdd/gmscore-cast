/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Serializes DNS-SD service resolves and rejects callbacks from retired
 * discovery scans or superseded service announcements.
 *
 * <p>All methods are called on the provider's main handler. An in-flight
 * platform resolve is intentionally kept after discovery stops: releasing
 * its slot before the NSD callback has returned could start a second resolve
 * while Android still considers the first one active.</p>
 */
final class CastDnsSdResolveQueue<T> {
    static final int MAX_ALREADY_ACTIVE_RETRIES = 2;

    static final class Request<T> {
        final String name;
        final T service;
        final long generation;
        final long serial;
        final int retries;

        Request(String name, T service, long generation, long serial, int retries) {
            this.name = name;
            this.service = service;
            this.generation = generation;
            this.serial = serial;
            this.retries = retries;
        }
    }

    private final ArrayDeque<Request<T>> pending = new ArrayDeque<>();
    private final Set<String> presentNames = new HashSet<>();
    private final Map<String, Long> currentSerials = new HashMap<>();
    private Request<T> inFlight;
    private long generation;
    private long serial;
    private boolean discovering;

    long beginDiscovery() {
        generation++;
        discovering = true;
        pending.clear();
        presentNames.clear();
        currentSerials.clear();
        // Keep inFlight until the OS has completed the prior resolve.
        return generation;
    }

    void endDiscovery() {
        generation++;
        discovering = false;
        pending.clear();
        presentNames.clear();
        currentSerials.clear();
    }

    boolean isDiscoveryCurrent(long expectedGeneration) {
        return discovering && generation == expectedGeneration;
    }

    void found(String name, T service) {
        if (!discovering || name == null || name.isEmpty() || service == null) return;
        long nextSerial = ++serial;
        presentNames.add(name);
        currentSerials.put(name, nextSerial);
        removeQueued(name);
        pending.addLast(new Request<>(name, service, generation, nextSerial, 0));
    }

    void lost(String name) {
        if (name == null) return;
        presentNames.remove(name);
        currentSerials.remove(name);
        removeQueued(name);
    }

    Request<T> takeNext() {
        if (!discovering || inFlight != null) return null;
        while (!pending.isEmpty()) {
            Request<T> next = pending.removeFirst();
            if (isCurrent(next)) {
                inFlight = next;
                return next;
            }
        }
        return null;
    }

    /**
     * Release a matching completed OS resolve. Returns whether its data may
     * be published. A stale callback still frees the slot for newer work.
     */
    boolean complete(Request<T> request) {
        if (request == null || request != inFlight) return false;
        inFlight = null;
        return isCurrent(request);
    }

    /** Retrying a superseded request must never restore a lost service. */
    boolean retryAlreadyActive(Request<T> request) {
        if (request == null || !isCurrent(request)
                || request.retries >= MAX_ALREADY_ACTIVE_RETRIES) return false;
        pending.addLast(new Request<>(request.name, request.service,
                request.generation, request.serial, request.retries + 1));
        return true;
    }

    private boolean isCurrent(Request<T> request) {
        return discovering && request.generation == generation
                && presentNames.contains(request.name)
                && Objects.equals(currentSerials.get(request.name), request.serial);
    }

    private void removeQueued(String name) {
        for (Iterator<Request<T>> it = pending.iterator(); it.hasNext();) {
            if (Objects.equals(name, it.next().name)) it.remove();
        }
    }
}
