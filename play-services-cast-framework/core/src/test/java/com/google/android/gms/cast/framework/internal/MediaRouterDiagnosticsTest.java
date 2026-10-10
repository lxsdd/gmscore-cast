/*
 * Copyright (C) 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaRouterDiagnosticsTest {
    private static final String SELF = "app.revanced.android.gms/provider:";
    private static final String VENDOR = "com.google.android.gms/provider:";

    @Test
    public void initialDiscoveryPublishesOneRoutePerPhysicalDevice() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();

        diagnostics.routeAdded(SELF + "living", "physical-living");
        MediaRouterDiagnostics.Snapshot snapshot =
                diagnostics.routeAdded(SELF + "bedroom", "physical-bedroom");

        assertEquals(2, snapshot.discoveryRouteCount);
        assertEquals(2, snapshot.physicalDeviceCount);
        assertEquals(0, snapshot.duplicatePhysicalIdentityCount);
        assertEquals(0, snapshot.unusedDuplicatePhysicalIdentityCount);
    }

    @Test
    public void duplicateFromSecondProviderIdentifiesUnusedDevice() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();
        diagnostics.routeAdded(SELF + "living", "physical-living");
        diagnostics.routeAdded(SELF + "bedroom", "physical-bedroom");
        diagnostics.routeSelected(SELF + "living", SELF + "living", null);

        MediaRouterDiagnostics.Snapshot snapshot =
                diagnostics.routeAdded(VENDOR + "bedroom", "physical-bedroom");

        assertEquals(3, snapshot.discoveryRouteCount);
        assertEquals(2, snapshot.physicalDeviceCount);
        assertEquals(1, snapshot.duplicatePhysicalIdentityCount);
        assertEquals(1, snapshot.unusedDuplicatePhysicalIdentityCount);
        assertEquals(2, snapshot.selfSourceCount);
        assertEquals(1, snapshot.vendorSourceCount);
    }

    @Test
    public void connectedCompletionIsDistinctFromBinderReturn() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();
        diagnostics.routeAdded(SELF + "living", "physical-living");
        diagnostics.routeSelected(SELF + "living", SELF + "living", null);
        diagnostics.routeConnected(SELF + "living", "mr2:connected", null);

        MediaRouterDiagnostics.Snapshot immediate =
                diagnostics.binderDisconnectReturned("mr2:connected");
        MediaRouterDiagnostics.Snapshot completed =
                diagnostics.routeDisconnected(SELF + "living", "mr2:connected");

        assertTrue(immediate.disconnectTargetExistsAfterImmediate);
        assertEquals(1, immediate.connectedRouteCount);
        assertTrue(completed.controllerReleased);
        assertEquals(0, completed.connectedRouteCount);
    }

    @Test
    public void endingSessionOnALeavesAAndUnusedBPublishedOnce() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();
        diagnostics.routeAdded(SELF + "living", "physical-living");
        diagnostics.routeAdded(SELF + "bedroom", "physical-bedroom");
        diagnostics.routeSelected(SELF + "living", SELF + "living", null);
        diagnostics.routeConnected(SELF + "living", "mr2:connected", null);

        diagnostics.routeDisconnected(SELF + "living", "mr2:connected");
        diagnostics.routeUnselected(SELF + "living");
        MediaRouterDiagnostics.Snapshot snapshot =
                diagnostics.routeAdded(SELF + "bedroom", "physical-bedroom");

        assertEquals(2, snapshot.routeCount);
        assertEquals(2, snapshot.discoveryRouteCount);
        assertEquals(2, snapshot.physicalDeviceCount);
        assertEquals(0, snapshot.duplicatePhysicalIdentityCount);
        assertEquals(0, snapshot.connectedRouteCount);
    }

    @Test
    public void repeatedUpdatesAndCyclesDoNotGrowRouteCount() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();
        diagnostics.routeAdded(SELF + "living", "physical-living");
        diagnostics.routeAdded(SELF + "living", "physical-living");

        for (int i = 0; i < 3; i++) {
            diagnostics.routeSelected(SELF + "living", SELF + "living", null);
            diagnostics.routeConnected(SELF + "living", "mr2:connected", null);
            diagnostics.routeDisconnected(SELF + "living", "mr2:connected");
            diagnostics.routeUnselected(SELF + "living");
        }
        MediaRouterDiagnostics.Snapshot snapshot =
                diagnostics.routeAdded(SELF + "living", "physical-living");

        assertEquals(1, snapshot.routeCount);
        assertEquals(1, snapshot.discoveryRouteCount);
        assertEquals(0, snapshot.connectedRouteCount);
        assertEquals(0, snapshot.selectedRouteCount);
        assertFalse(snapshot.controllerReleased);
    }

    @Test
    public void routeWithoutCastIdentityRemainsVisibleInCounters() {
        MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();

        MediaRouterDiagnostics.Snapshot snapshot =
                diagnostics.routeAdded("mr2:unmapped", (String) null);

        assertEquals(1, snapshot.otherSourceCount);
        assertEquals(1, snapshot.routeWithoutPhysicalIdentityCount);
        assertEquals(0, snapshot.physicalDeviceCount);
    }
}
