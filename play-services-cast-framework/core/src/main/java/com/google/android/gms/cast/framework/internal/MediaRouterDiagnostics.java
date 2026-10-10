/*
 * Copyright (C) 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;

import com.google.android.gms.cast.CastDevice;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class MediaRouterDiagnostics {
    private static final String VENDOR_GMS_PACKAGE = "com.google.android.gms";
    private static final String REVANCED_GMS_PACKAGE = "app.revanced.android.gms";
    private static final String MICROG_GMS_PACKAGE = "org.microg.gms";
    private static final AtomicInteger CALLBACK_INSTANCE_COUNT = new AtomicInteger();

    private final Map<String, RouteState> routes = new HashMap<>();
    private String selectedRouteId;
    private String lastSessionPhysicalId;

    MediaRouterDiagnostics() {
        CALLBACK_INSTANCE_COUNT.incrementAndGet();
    }

    synchronized Snapshot routeAdded(String routeId, Bundle extras) {
        RouteState state = stateFor(routeId, physicalIdFrom(extras));
        state.discoveryBacked = true;
        return snapshot();
    }

    synchronized Snapshot routeAdded(String routeId, String physicalId) {
        RouteState state = stateFor(routeId, physicalId);
        state.discoveryBacked = true;
        return snapshot();
    }

    synchronized Snapshot routeChanged(String routeId, Bundle extras) {
        RouteState state = stateFor(routeId, physicalIdFrom(extras));
        state.discoveryBacked = true;
        return snapshot();
    }

    synchronized Snapshot routeRemoved(String routeId) {
        routes.remove(routeId);
        if (same(selectedRouteId, routeId)) selectedRouteId = null;
        return snapshot();
    }

    synchronized Snapshot routeSelected(String requestedRouteId, String selectedRouteId,
            Bundle extras) {
        RouteState state = stateFor(selectedRouteId, physicalIdFrom(extras));
        state.selected = true;
        clearOtherSelected(selectedRouteId);
        this.selectedRouteId = selectedRouteId;
        this.lastSessionPhysicalId = state.physicalId;
        Snapshot snapshot = snapshot();
        snapshot.sessionRouteEqualsSelectedRoute = same(requestedRouteId, selectedRouteId);
        return snapshot;
    }

    synchronized Snapshot routeConnected(String requestedRouteId, String connectedRouteId,
            Bundle extras) {
        RouteState state = stateFor(connectedRouteId, physicalIdFrom(extras));
        state.connected = true;
        Snapshot snapshot = snapshot();
        snapshot.sessionRouteEqualsConnectedRoute = same(requestedRouteId, connectedRouteId);
        snapshot.samePhysicalDevice = samePhysical(requestedRouteId, connectedRouteId);
        return snapshot;
    }

    synchronized Snapshot routeUnselected(String routeId) {
        RouteState state = routes.get(routeId);
        if (state != null) state.selected = false;
        if (same(selectedRouteId, routeId)) selectedRouteId = null;
        return snapshot();
    }

    synchronized Snapshot routeDisconnected(String requestedRouteId, String disconnectedRouteId) {
        RouteState state = routes.get(disconnectedRouteId);
        boolean controllerReleased = state != null && state.connected;
        if (state != null && state.discoveryBacked) {
            state.connected = false;
        } else {
            routes.remove(disconnectedRouteId);
        }
        Snapshot snapshot = snapshot();
        snapshot.sessionRouteEqualsConnectedRoute = same(requestedRouteId, disconnectedRouteId);
        snapshot.samePhysicalDevice = samePhysical(requestedRouteId, disconnectedRouteId);
        snapshot.controllerReleased = controllerReleased;
        return snapshot;
    }

    synchronized Snapshot sessionEndRequest(String routeId) {
        Snapshot snapshot = snapshot();
        snapshot.disconnectTargetExists = routes.containsKey(routeId);
        snapshot.sessionRouteEqualsSelectedRoute = same(routeId, selectedRouteId);
        RouteState state = routes.get(routeId);
        snapshot.sessionRouteEqualsConnectedRoute = state != null && state.connected;
        return snapshot;
    }

    synchronized Snapshot binderDisconnectReturned(String routeId) {
        Snapshot snapshot = snapshot();
        snapshot.disconnectTargetExists = routes.containsKey(routeId);
        RouteState state = routes.get(routeId);
        snapshot.disconnectTargetExistsAfterImmediate = state != null && state.connected;
        return snapshot;
    }

    private RouteState stateFor(String routeId, String physicalId) {
        RouteState state = routes.get(routeId);
        if (state == null) {
            state = new RouteState(sourceOf(routeId));
            routes.put(routeId, state);
        }
        if (physicalId != null) state.physicalId = physicalId;
        return state;
    }

    private static String physicalIdFrom(Bundle extras) {
        CastDevice device = CastDevice.getFromBundle(extras);
        return device == null ? null : device.getDeviceId();
    }

    private void clearOtherSelected(String selectedRouteId) {
        for (Map.Entry<String, RouteState> entry : routes.entrySet()) {
            if (!same(entry.getKey(), selectedRouteId)) entry.getValue().selected = false;
        }
    }

    private boolean samePhysical(String firstRouteId, String secondRouteId) {
        RouteState first = routes.get(firstRouteId);
        RouteState second = routes.get(secondRouteId);
        return first != null && second != null && first.physicalId != null
                && first.physicalId.equals(second.physicalId);
    }

    private Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.callbackInstanceCount = CALLBACK_INSTANCE_COUNT.get();
        snapshot.routeCount = routes.size();
        Map<String, Integer> physicalCounts = new HashMap<>();
        Set<String> physicalDevices = new HashSet<>();
        for (RouteState state : routes.values()) {
            if (state.discoveryBacked) snapshot.discoveryRouteCount++;
            if (state.connected) snapshot.connectedRouteCount++;
            if (state.selected) snapshot.selectedRouteCount++;
            if (state.source == Source.SELF) snapshot.selfSourceCount++;
            else if (state.source == Source.VENDOR) snapshot.vendorSourceCount++;
            else snapshot.otherSourceCount++;
            if (state.physicalId != null) {
                physicalDevices.add(state.physicalId);
                physicalCounts.put(state.physicalId,
                        physicalCounts.containsKey(state.physicalId)
                                ? physicalCounts.get(state.physicalId) + 1 : 1);
            } else {
                snapshot.routeWithoutPhysicalIdentityCount++;
            }
        }
        snapshot.physicalDeviceCount = physicalDevices.size();
        for (Map.Entry<String, Integer> entry : physicalCounts.entrySet()) {
            if (entry.getValue() > 1) {
                snapshot.duplicatePhysicalIdentityCount++;
                if (!same(entry.getKey(), lastSessionPhysicalId)) {
                    snapshot.unusedDuplicatePhysicalIdentityCount++;
                }
            }
        }
        return snapshot;
    }

    private static Source sourceOf(String routeId) {
        if (routeId != null && (routeId.contains(REVANCED_GMS_PACKAGE)
                || routeId.contains(MICROG_GMS_PACKAGE))) return Source.SELF;
        if (routeId != null && routeId.contains(VENDOR_GMS_PACKAGE)) return Source.VENDOR;
        return Source.OTHER;
    }

    private static boolean same(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private enum Source { SELF, VENDOR, OTHER }

    private static final class RouteState {
        final Source source;
        String physicalId;
        boolean discoveryBacked;
        boolean selected;
        boolean connected;

        RouteState(Source source) {
            this.source = source;
        }
    }

    static final class Snapshot {
        int callbackInstanceCount;
        int routeCount;
        int physicalDeviceCount;
        int duplicatePhysicalIdentityCount;
        int unusedDuplicatePhysicalIdentityCount;
        int discoveryRouteCount;
        int connectedRouteCount;
        int selectedRouteCount;
        int selfSourceCount;
        int vendorSourceCount;
        int otherSourceCount;
        int routeWithoutPhysicalIdentityCount;
        boolean sessionRouteEqualsSelectedRoute;
        boolean sessionRouteEqualsConnectedRoute;
        boolean samePhysicalDevice;
        boolean disconnectTargetExists;
        boolean disconnectTargetExistsAfterImmediate;
        boolean controllerReleased;
    }
}
