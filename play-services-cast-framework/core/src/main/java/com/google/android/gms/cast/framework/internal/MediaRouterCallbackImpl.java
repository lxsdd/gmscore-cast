/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.dynamic.ObjectWrapper;

public class MediaRouterCallbackImpl extends IMediaRouterCallback.Stub {
    private static final String TAG = MediaRouterCallbackImpl.class.getSimpleName();

    private CastContextImpl castContext;
    private final MediaRouterDiagnostics diagnostics = new MediaRouterDiagnostics();

    public MediaRouterCallbackImpl(CastContextImpl castContext) {
        this.castContext = castContext;
    }

    @Override
    public void onRouteAdded(String routeId, Bundle extras) {
        logRouteState("routeAdded", diagnostics.routeAdded(routeId, extras));
    }
    @Override
    public void onRouteChanged(String routeId, Bundle extras) {
        logRouteState("routeChanged", diagnostics.routeChanged(routeId, extras));
        this.castContext.getSessionManagerImpl().onRouteChanged(routeId, extras);
    }
    @Override
    public void onRouteRemoved(String routeId, Bundle extras) {
        logRouteState("routeRemoved", diagnostics.routeRemoved(routeId));
    }
    @Override
    public void onRouteSelected(String routeId, Bundle extras) throws RemoteException {
        logRouteState("routeSelected",
                diagnostics.routeSelected(routeId, routeId, extras));
        startSelectedRoute(routeId, extras);
    }

    private void startSelectedRoute(String routeId, Bundle extras) throws RemoteException {
        Log.i(TAG, "selectionRequest selectionCause=UNKNOWN routePresent=" + (routeId != null));
        Bundle routeInfoExtras = this.castContext.getRouter().getRouteInfoExtrasById(routeId);
        Bundle effectiveExtras = routeInfoExtras != null ? routeInfoExtras : extras;
        CastDevice castDevice = CastDevice.getFromBundle(effectiveExtras);
        Log.i(TAG, "routeBundleSelection callbackBundlePresent=" + (extras != null)
                + " callbackCastDevicePresent=" + (CastDevice.getFromBundle(extras) != null)
                + " queriedBundlePresent=" + (routeInfoExtras != null)
                + " queriedCastDevicePresent=" + (CastDevice.getFromBundle(routeInfoExtras) != null)
                + " usingQueriedBundle=" + (routeInfoExtras != null));

        SessionImpl current = this.castContext.getSessionManagerImpl().getCurrentSession();
        if (current != null && routeId != null && routeId.equals(current.getRouteId())) {
            if (current.isSuspended()) {
                Log.i(TAG, "routeToSession action=RESUME");
                current.resume(this.castContext, castDevice, routeId, effectiveExtras);
            } else {
                Log.i(TAG, "routeToSession action=KEEP_CURRENT");
            }
            return;
        }

        if (this.castContext.defaultSessionProvider == null) {
            Log.w(TAG, "No session provider for selected route; cannot start session");
            return;
        }
        Object session = ObjectWrapper.unwrap(this.castContext.defaultSessionProvider.getSession(null));
        if (!(session instanceof SessionImpl)) {
            Log.w(TAG, "Session provider did not yield a SessionImpl for selected route");
            return;
        }
        Log.i(TAG, "routeToSession action=START castDevicePresent=" + (castDevice != null));
        ((SessionImpl) session).start(this.castContext, castDevice, routeId, effectiveExtras);
    }
    @Override
    public void unknown(String routeId, Bundle extras) {
        Log.d(TAG, "unimplemented Method: unknown");
    }
    @Override
    public void onRouteUnselected(String routeId, Bundle extras, int reason) {
        // AndroidX MediaRouter uses reason 1 for Disconnect/Leave and reason 2 for Stop Casting.
        // Only the latter requests that the receiver application itself be stopped.
        boolean stopCasting = reason == 2;
        logRouteState("routeUnselected reason=" + reason + " stopCasting=" + stopCasting,
                diagnostics.routeUnselected(routeId));
        this.castContext.getSessionManagerImpl().onRouteUnselected(routeId, stopCasting);
    }

    @Override
    public int getSupportedVersion() {
        // Support the current selected/connected/disconnected callback contract so route
        // teardown can be correlated with its actual AndroidX completion event.
        return 263210000;
    }

    @Override
    public void onRouteSelectedNew(String requestedRouteId, String selectedRouteId, Bundle extras)
            throws RemoteException {
        MediaRouterDiagnostics.Snapshot snapshot = diagnostics.routeSelected(
                requestedRouteId, selectedRouteId, extras);
        logRouteState("routeSelectedModern", snapshot);
        startSelectedRoute(selectedRouteId, extras);
    }

    @Override
    public void onRouteConnected(String requestedRouteId, String connectedRouteId, Bundle extras) {
        MediaRouterDiagnostics.Snapshot snapshot = diagnostics.routeConnected(
                requestedRouteId, connectedRouteId, extras);
        logRouteState("routeConnected", snapshot);
    }

    @Override
    public void onRouteDisconnected(String requestedRouteId, String disconnectedRouteId,
            Bundle extras, int reason) {
        MediaRouterDiagnostics.Snapshot snapshot = diagnostics.routeDisconnected(
                requestedRouteId, disconnectedRouteId);
        logRouteState("routeDisconnectCompleted reason=" + reason, snapshot);
        this.castContext.getSessionManagerImpl().onRouteConnectionLost(
                disconnectedRouteId,
                com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST);
    }

    void onSessionEndRequest(String routeId) {
        logRouteState("sessionEndRequest", diagnostics.sessionEndRequest(routeId));
    }

    void onDisconnectBinderReturned(String routeId) {
        logRouteState("disconnectBinderReturned",
                diagnostics.binderDisconnectReturned(routeId));
    }

    private static void logRouteState(String event, MediaRouterDiagnostics.Snapshot state) {
        Log.i(TAG, "routeState event=" + event
                + " callbackCreatedTotal=" + state.callbackInstanceCount
                + " routeCount=" + state.routeCount
                + " physicalDeviceCount=" + state.physicalDeviceCount
                + " duplicatePhysicalIdentityCount=" + state.duplicatePhysicalIdentityCount
                + " unusedDuplicatePhysicalIdentityCount="
                + state.unusedDuplicatePhysicalIdentityCount
                + " discoveryRouteCount=" + state.discoveryRouteCount
                + " connectedRouteCount=" + state.connectedRouteCount
                + " selectedRouteCount=" + state.selectedRouteCount
                + " selfSourceCount=" + state.selfSourceCount
                + " vendorSourceCount=" + state.vendorSourceCount
                + " otherSourceCount=" + state.otherSourceCount
                + " routeWithoutPhysicalIdentityCount="
                + state.routeWithoutPhysicalIdentityCount
                + " sessionRouteEqualsSelectedRoute="
                + state.sessionRouteEqualsSelectedRoute
                + " sessionRouteEqualsConnectedRoute="
                + state.sessionRouteEqualsConnectedRoute
                + " samePhysicalDevice=" + state.samePhysicalDevice
                + " disconnectTargetExists=" + state.disconnectTargetExists
                + " disconnectTargetExistsAfterImmediate="
                + state.disconnectTargetExistsAfterImmediate
                + " controllerReleased=" + state.controllerReleased);
    }
}
