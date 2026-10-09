/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            devices.put(id, device);
            serviceCastIds.put(serviceName, id);
        }
    }

    synchronized void forgetService(String serviceName) {
        String id = serviceCastIds.remove(serviceName);
        if (id != null) {
            devices.remove(id);
        }
    }

    synchronized void addCategory(String category) {
        categories.add(category);
    }

    synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(new ArrayList<>(devices.values()),
                new ArrayList<>(categories));
    }
}
