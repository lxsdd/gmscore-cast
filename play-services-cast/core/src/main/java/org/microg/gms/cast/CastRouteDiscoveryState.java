/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the collection state consumed by Cast route publication.
 *
 * <p>Android's DNS-SD callbacks and MediaRouter callbacks need not use the same
 * thread. A route descriptor must never iterate a HashMap or ArrayList while
 * a concurrent discovery callback mutates it. All mutations and snapshot
 * acquisition therefore use the same lock. The snapshot itself is detached,
 * so expensive descriptor construction happens outside that lock.</p>
 *
 * <p>This class intentionally preserves the existing route-registration
 * semantics. Discovery generation, late resolve rejection, and service alias
 * reconciliation are separate changes.</p>
 */
final class CastRouteDiscoveryState<T> {
    private final Map<String, T> devices = new HashMap<>();
    private final Map<String, String> serviceCastIds = new HashMap<>();
    private final List<String> categories = new ArrayList<>();
    // Token identity ties route retention to the actual selecting controller,
    // rather than the mere existence of a RouteController instance.
    private final Map<String, Set<Object>> selectedOwners = new HashMap<>();

    static final class Snapshot<T> {
        final List<T> devices;
        final List<String> categories;

        Snapshot(List<T> devices, List<String> categories) {
            this.devices = devices;
            this.categories = categories;
        }
    }

    synchronized T getDevice(String id) {
        return devices.get(id);
    }

    synchronized void rememberIfAbsent(String id, String serviceName, T device) {
        if (!devices.containsKey(id)) {
            putDevice(id, serviceName, device, false);
        }
    }

    /**
     * A later DNS-SD advertisement can change capabilities or the endpoint of
     * an existing ID. Reconcile the service-name mapping atomically.
     */
    synchronized boolean putDevice(String id, String serviceName, T device,
                                   boolean replaceExisting) {
        T oldDevice = devices.get(id);
        boolean changed = oldDevice == null || replaceExisting;
        if (changed) devices.put(id, device);
        String previousId = serviceCastIds.put(serviceName, id);
        if (previousId != null && !Objects.equals(previousId, id)
                && !serviceCastIds.containsValue(previousId)) {
            removeIfUnused(previousId);
        }
        return changed || !Objects.equals(previousId, id);
    }

    synchronized void forgetService(String serviceName) {
        String id = serviceCastIds.remove(serviceName);
        if (id != null && !serviceCastIds.containsValue(id)) {
            removeIfUnused(id);
        }
    }

    /** A selected route survives temporary DNS-SD loss until its controller releases it. */
    synchronized boolean selectRoute(String id, Object controllerToken) {
        if (id == null || controllerToken == null || !devices.containsKey(id)) return false;
        Set<Object> tokens = selectedOwners.get(id);
        if (tokens == null) {
            tokens = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            selectedOwners.put(id, tokens);
        }
        return tokens.add(controllerToken);
    }

    /** Release by exact controller identity; no stale controller can release another one. */
    synchronized boolean releaseRoute(String id, Object controllerToken) {
        if (id == null || controllerToken == null) return false;
        Set<Object> tokens = selectedOwners.get(id);
        if (tokens == null || !tokens.remove(controllerToken)) return false;
        if (tokens.isEmpty()) {
            selectedOwners.remove(id);
            if (!serviceCastIds.containsValue(id)) {
                devices.remove(id);
            }
        }
        return true;
    }

    synchronized boolean isSelected(String id) {
        Set<Object> tokens = selectedOwners.get(id);
        return tokens != null && !tokens.isEmpty();
    }

    private void removeIfUnused(String id) {
        if (!isSelected(id)) devices.remove(id);
    }

    synchronized void addCategory(String category) {
        categories.add(category);
    }

    /** A chooser reopen is a new selector, not an append-only filter history. */
    synchronized boolean replaceCategories(List<String> requested) {
        List<String> next = new ArrayList<>();
        if (requested != null) {
            for (String category : new LinkedHashSet<>(requested)) {
                if (category != null && !category.isEmpty()) next.add(category);
            }
        }
        if (categories.equals(next)) return false;
        categories.clear();
        categories.addAll(next);
        return true;
    }

    synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(new ArrayList<>(devices.values()),
                new ArrayList<>(categories));
    }
}
