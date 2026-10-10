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

import com.google.android.gms.cast.framework.ICastSession;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.ICastConnectionController;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

public class CastSessionImpl extends ICastSession.Stub {
    private static final String TAG = CastSessionImpl.class.getSimpleName();
    private CastOptions options;
    private SessionImpl session;
    private ICastConnectionController controller;
    private String receiverSessionId;
    private boolean callerProvidedReceiver;

    public CastSessionImpl(CastOptions options, IObjectWrapper session, ICastConnectionController controller) throws RemoteException {
        this.options = options;
        this.session = (SessionImpl) ObjectWrapper.unwrap(session);
        this.controller = controller;

        this.session.setCastSession(this);
    }

    public void launchApplication() throws RemoteException {
        launchApplication(this.session.getRouteInfoExtra());
    }

    @Override
    public void onConnected(Bundle routeInfoExtra) throws RemoteException {
        Bundle effectiveRouteInfoExtra = effectiveRouteInfoExtra(routeInfoExtra, this.session);
        Log.i(TAG, "RECEIVER_SELECTION: stage=TRANSPORT_CONNECTED appCategory=CLIENT_CONFIGURED"
                + " routeInfoPresent=" + (effectiveRouteInfoExtra != null)
                + " callbackRouteInfoPresent=" + (routeInfoExtra != null)
                + " sessionFallbackUsed=" + (routeInfoExtra == null
                && effectiveRouteInfoExtra != null));

        if (session.isResuming() || session.isSuspended()) {
            String applicationId = CastSessionLaunchRequest.receiverApplicationId(
                    options.getReceiverApplicationId(), effectiveRouteInfoExtra);
            String joinSessionId = receiverSessionId != null
                    ? receiverSessionId : session.getSessionId();
            Log.i(TAG, "sessionReconnect action=JOIN sessionPresent=" + (joinSessionId != null));
            controller.joinApplication(applicationId, joinSessionId);
            return;
        }
        launchApplication(effectiveRouteInfoExtra);
    }

    static Bundle effectiveRouteInfoExtra(Bundle callbackRouteInfoExtra, SessionImpl session) {
        return callbackRouteInfoExtra != null
                ? callbackRouteInfoExtra : session.getRouteInfoExtra();
    }

    private void launchApplication(Bundle routeInfoExtra) throws RemoteException {
        String configuredApplicationId = this.options.getReceiverApplicationId();
        String applicationId = CastSessionLaunchRequest.receiverApplicationId(
                configuredApplicationId, routeInfoExtra);
        this.callerProvidedReceiver = CastSessionLaunchRequest.hasCallerProvidedReceiver(
                configuredApplicationId, applicationId);
        Log.i(TAG, "RECEIVER_SELECTION: stage=LAUNCH_FORWARD appCategory="
                + (this.callerProvidedReceiver ? "CALLER_PROVIDED" : "CLIENT_CONFIGURED")
                + " receiverConfigured=" + (applicationId != null));
        this.controller.launchApplication(applicationId, this.options.getLaunchOptions());
    }

    @Override
    public void onConnectionSuspended(int reason) {
        Log.i(TAG, "onConnectionSuspended reason=" + reason);
        session.notifySessionSuspended(reason);
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        int error = connectionResult == null
                ? CastStatusCodes.INTERNAL_ERROR : connectionResult.getErrorCode();
        Log.i(TAG, "onConnectionFailed error=" + error);
        onFailure(error);
    }

    @Override
    public void onApplicationConnectionSuccess(ApplicationMetadata applicationMetadata, String applicationStatus, String sessionId, boolean wasLaunched) {
        Log.i(TAG, "onApplicationConnectionSuccess sessionPresent=" + (sessionId != null)
                + " wasLaunched=" + wasLaunched);
        if (this.callerProvidedReceiver) {
            Log.i(TAG, "GENERIC_MEDIA_GATE stage=DMR_LAUNCH_RESULT success=true");
        }
        this.receiverSessionId = sessionId;
        if (this.session.isResuming()) {
            this.session.notifySessionResumed(this.session.wasSuspendedBeforeResume());
        } else if (this.session.isSuspended()) {
            this.session.notifySessionResumed(true);
        } else {
            this.session.onApplicationConnectionSuccess(
                    applicationMetadata, applicationStatus, sessionId, wasLaunched);
        }
    }

    @Override
    public void onApplicationConnectionFailure(int statusCode) {
        if (this.callerProvidedReceiver) {
            Log.i(TAG, "GENERIC_MEDIA_GATE stage=DMR_LAUNCH_RESULT success=false");
        }
        onFailure(statusCode);
    }

    private void onFailure(int statusCode) {
        boolean accepted;
        if (this.session.isResuming()) {
            this.session.notifyFailedToResumeSession(statusCode);
            accepted = true;
        } else if (this.session.isConnecting()) {
            accepted = this.session.onApplicationConnectionFailure(statusCode);
        } else if (this.session.isConnected() || this.session.isSuspended()) {
            this.session.notifySessionEnded(statusCode);
            accepted = true;
        } else {
            accepted = false;
        }
        Log.i(TAG, "FAILURE_LIFECYCLE: stage=CAST_SESSION statusCode=" + statusCode
                + " accepted=" + accepted
                + " controllerPresent=" + (this.controller != null)
                + " controllerCloseRequested=" + accepted);
        if (!accepted || this.controller == null) return;

        this.receiverSessionId = null;
        try {
            this.controller.closeConnection(statusCode);
            Log.i(TAG, "FAILURE_LIFECYCLE: stage=CONTROLLER_CLOSE statusCode=" + statusCode
                    + " requested=true completed=true");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "FAILURE_LIFECYCLE: stage=CONTROLLER_CLOSE statusCode=" + statusCode
                    + " requested=true completed=false");
        }
    }

    @Override
    public void disconnectFromDevice(boolean stopCasting, int reason) throws RemoteException {
        Log.i(TAG, "disconnectFromDevice stopCasting=" + stopCasting + " reason=" + reason
                + " sessionPresent=" + (this.receiverSessionId != null));
        try {
            if (stopCasting && this.receiverSessionId != null) {
                Log.i(TAG, "stopApplication requested=true");
                this.controller.stopApplication(this.receiverSessionId);
            }
        } finally {
            // Closing the sender connection is distinct from stopping the receiver app and must
            // happen afterwards so the STOP command is not cut off in transit.
            Log.i(TAG, "transportClose requested=true");
            this.controller.closeConnection(reason);
            this.receiverSessionId = null;
        }
    }
}
