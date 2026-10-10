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


import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;
import com.google.android.gms.cast.framework.ISession;
import com.google.android.gms.cast.framework.ISessionProxy;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import java.util.concurrent.atomic.AtomicLong;

public class SessionImpl extends ISession.Stub {
    private static final String TAG = SessionImpl.class.getSimpleName();
    private static final AtomicLong NEXT_SESSION_GENERATION = new AtomicLong();

    enum LifecycleState {
        NEW,
        STARTING,
        RESUMING,
        STARTED,
        SUSPENDED,
        ENDING,
        ENDED
    }

    private static final int START_TYPE_NEW = 0;
    private static final int START_TYPE_RESUMED = 1;

    private String category;
    private String sessionId;
    private ISessionProxy proxy;

    private CastSessionImpl castSession;

    private CastContextImpl castContext;
    private CastDevice castDevice;
    private Bundle routeInfoExtra;

    private boolean mIsConnecting = false;
    private boolean mIsConnected = false;
    private String routeId = null;
    private final long sessionGeneration = NEXT_SESSION_GENERATION.incrementAndGet();
    private volatile LifecycleState lifecycleState = LifecycleState.NEW;
    private long controllerGeneration;
    private boolean sessionOwnsController;
    private boolean startCompletionAfterControllerRelease;
    private boolean resumeWasSuspended;
    private int startType = START_TYPE_NEW;

    public SessionImpl(String category, String sessionId, ISessionProxy proxy) {
        this.category = category;
        this.sessionId = sessionId;
        this.proxy = proxy;
    }

    public void start(CastContextImpl castContext, CastDevice castDevice, String routeId, Bundle routeInfoExtra) throws RemoteException {
        attach(castContext, castDevice, routeId, routeInfoExtra);
        this.resumeWasSuspended = false;
        this.startType = START_TYPE_NEW;
        if (!markStarting()) return;

        boolean audioOut = hasCapability(castDevice, CastDevice.CAPABILITY_AUDIO_OUT);
        boolean videoOut = hasCapability(castDevice, CastDevice.CAPABILITY_VIDEO_OUT);
        boolean group = hasCapability(castDevice, CastDevice.CAPABILITY_MULTIZONE_GROUP)
                || hasCapability(castDevice, CastDevice.CAPABILITY_DYNAMIC_GROUP);
        Log.i(TAG, "RECEIVER_SELECTION: appCategory=CLIENT_CONFIGURED serviceCategory="
                + serviceCategory()
                + " routeAudioOnly=" + (audioOut && !videoOut)
                + " group=" + group
                + " audioOut=" + audioOut
                + " videoOut=" + videoOut);

        try {
            Log.i(TAG, "sessionProxyOnStarting enter routeBundlePresent=" + (routeInfoExtra != null));
            this.proxy.onStarting(routeInfoExtra);
            if (lifecycleState != LifecycleState.STARTING) return;
            Log.i(TAG, "sessionProxyOnStarting return");
            if (!this.castContext.getSessionManagerImpl().onSessionStarting(this)) {
                Log.w(TAG, "sessionStartRejected staleGeneration=true" + lifecycleFields(false));
                markStartFailed();
                return;
            }
            if (lifecycleState != LifecycleState.STARTING) return;
            this.proxy.start(routeInfoExtra);
        } catch (RemoteException e) {
            notifyFailedToStartSession(com.google.android.gms.cast.CastStatusCodes.INTERNAL_ERROR);
            throw e;
        }
    }

    public void resume(CastContextImpl castContext, CastDevice castDevice, String routeId,
                       Bundle routeInfoExtra) throws RemoteException {
        boolean wasSuspended = lifecycleState == LifecycleState.SUSPENDED;
        attach(castContext, castDevice, routeId, routeInfoExtra);
        this.resumeWasSuspended = wasSuspended;
        this.startType = START_TYPE_RESUMED;
        if (!markResuming()) return;
        try {
            this.proxy.onResuming(routeInfoExtra);
            if (lifecycleState != LifecycleState.RESUMING) return;
            this.castContext.getSessionManagerImpl().onSessionResuming(this, sessionId);
            if (lifecycleState != LifecycleState.RESUMING) return;
            this.proxy.resume(routeInfoExtra);
        } catch (RemoteException e) {
            notifyFailedToResumeSession(com.google.android.gms.cast.CastStatusCodes.INTERNAL_ERROR);
            throw e;
        }
    }

    public void end(boolean stopCasting) throws RemoteException {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        end(stopCasting, context.getSessionManagerImpl());
    }

    void end(boolean stopCasting, SessionManagerImpl manager) throws RemoteException {
        if (manager == null || !manager.onSessionEnding(this)) return;
        try {
            this.proxy.end(stopCasting);
        } catch (RemoteException e) {
            notifySessionEnded(com.google.android.gms.cast.CastStatusCodes.INTERNAL_ERROR);
            throw e;
        }
    }

    private void attach(CastContextImpl castContext, CastDevice castDevice, String routeId,
                        Bundle routeInfoExtra) {
        CastRouteLifecycleRegistry.Snapshot controller =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        this.controllerGeneration = controller.generation;
        this.sessionOwnsController = controller.ownsController;
        this.castContext = castContext;
        this.castDevice = castDevice;
        this.routeInfoExtra = routeInfoExtra;
        this.routeId = routeId;
        Log.i(TAG, "sessionAttach routeInfoPresent=" + (routeInfoExtra != null)
                + lifecycleFields(false));
    }

    public void onRouteInfoUpdated(Bundle routeInfoExtra) {
        CastDevice updated = CastDevice.getFromBundle(routeInfoExtra);
        if (updated == null) return;
        this.castDevice = updated;
        this.routeInfoExtra = routeInfoExtra;
        try {
            this.proxy.onRouteInfoUpdated(routeInfoExtra);
        } catch (RemoteException e) {
            Log.d(TAG, "Remote exception calling onRouteInfoUpdated: " + e.getMessage());
        }
    }

    public void onApplicationConnectionSuccess(ApplicationMetadata applicationMetadata, String applicationStatus, String sessionId, boolean wasLaunched) {
        CastContextImpl context = this.castContext;
        boolean controllerReleased = isControllerReleased();
        this.startCompletionAfterControllerRelease = controllerReleased;
        boolean accepted = context != null
                && completeApplicationConnectionSuccess(context.getSessionManagerImpl(), sessionId);
        Log.i(TAG, "applicationConnectionSuccess sessionPresent=" + (sessionId != null)
                + " wasLaunched=" + wasLaunched
                + " accepted=" + accepted
                + lifecycleFields(controllerReleased));
        if (!accepted) {
            Log.w(TAG, "staleStartCompletion ignored=true" + lifecycleFields(controllerReleased));
        }
        Log.i(TAG, "selectionRequest selectionCause=FRAMEWORK_RESELECT suppressed=true"
                + " sessionGeneration=" + sessionGeneration);
    }

    public boolean onApplicationConnectionFailure(int statusCode) {
        CastContextImpl context = this.castContext;
        String failedRouteId = this.routeId;
        LifecycleState stateBefore = this.lifecycleState;
        boolean accepted = context != null
                && completeApplicationConnectionFailure(context.getSessionManagerImpl(), statusCode);
        boolean controllerReleased = isControllerReleased();
        Log.i(TAG, "FAILURE_LIFECYCLE: stage=SESSION_SETTLE statusCode=" + statusCode
                + " stateBefore=" + stateBefore
                + " accepted=" + accepted
                + " contextPresent=" + (context != null)
                + " routePresent=" + (failedRouteId != null)
                + " controllerPresent=" + (controllerGeneration != 0)
                + " controllerReleased=" + controllerReleased
                + " cleanupRequested=" + accepted);
        if (!accepted) {
            return false;
        }
        try {
            cleanupRoute(context.getRouter(), failedRouteId);
            return true;
        } finally {
            CastRouteLifecycleRegistry.forget(controllerGeneration);
            clearLocalState();
            Log.i(TAG, "FAILURE_LIFECYCLE: stage=SESSION_CLEANUP statusCode=" + statusCode
                    + " accepted=true localStateCleared=true controllerRegistryForgotten=true");
        }
    }

    public void onRouteSelected(Bundle extras) {
    }

    public CastSessionImpl getCastSession() {
        return this.castSession;
    }

    Bundle getRouteInfoExtra() {
        return this.routeInfoExtra;
    }

    public void setCastSession(CastSessionImpl castSession) {
        this.castSession = castSession;
    }

    public ISessionProxy getSessionProxy() {
        return this.proxy;
    }

    public IObjectWrapper getWrappedSession() throws RemoteException {
        if (this.proxy == null) {
            return ObjectWrapper.wrap(null);
        }
        return this.proxy.getWrappedSession();
    }

    @Override
    public String getCategory() {
        return this.category;
    }

    @Override
    public String getSessionId() {
        return this.sessionId;
    }

    @Override
    public String getRouteId() {
        return this.routeId;
    }

    @Override
    public boolean isConnected() {
        return this.mIsConnected;
    }

    @Override
    public boolean isConnecting() {
        return this.mIsConnecting;
    }

    @Override
    public boolean isDisconnecting() {
        return lifecycleState == LifecycleState.ENDING;
    }

    @Override
    public boolean isDisconnected() {
        return lifecycleState == LifecycleState.ENDED;
    }

    @Override
    public boolean isResuming() {
        return lifecycleState == LifecycleState.RESUMING;
    }

    @Override
    public boolean isSuspended() {
        return lifecycleState == LifecycleState.SUSPENDED;
    }

    boolean wasSuspendedBeforeResume() {
        return resumeWasSuspended;
    }

    @Override
    public void notifySessionStarted(String sessionId) {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        boolean accepted = context.getSessionManagerImpl().onSessionStarted(this, sessionId);
        if (!accepted) {
            Log.w(TAG, "staleSessionStarted ignored=true" + lifecycleFields(isControllerReleased()));
        }
    }

    @Override
    public void notifyFailedToStartSession(int error) {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        onApplicationConnectionFailure(error);
    }

    @Override
    public void notifySessionEnded(int error) {
        Log.i(TAG, "notifySessionEnded enter error=" + error + lifecycleFields(isControllerReleased()));
        CastContextImpl context = this.castContext;
        String endedRouteId = this.routeId;
        boolean shouldCleanupRoute = false;
        try {
            if (context != null) {
                MediaRouterCallbackImpl callback = context.getMediaRouterCallback();
                if (callback != null) callback.onSessionEndRequest(endedRouteId);
                shouldCleanupRoute = context.getSessionManagerImpl().onSessionEnded(this, error);
            }
        } finally {
            if (context != null && shouldCleanupRoute) {
                cleanupRoute(context.getRouter(), endedRouteId);
                MediaRouterCallbackImpl callback = context.getMediaRouterCallback();
                if (callback != null) callback.onDisconnectBinderReturned(endedRouteId);
            }
            CastRouteLifecycleRegistry.forget(controllerGeneration);
            clearLocalState();
            Log.i(TAG, "notifySessionEnded return localStateCleared=true"
                    + lifecycleFields(true));
        }
    }

    synchronized boolean markStarting() {
        if (lifecycleState != LifecycleState.NEW) return false;
        lifecycleState = LifecycleState.STARTING;
        mIsConnecting = true;
        mIsConnected = false;
        return true;
    }

    synchronized boolean markResuming() {
        if (lifecycleState != LifecycleState.NEW
                && lifecycleState != LifecycleState.SUSPENDED) return false;
        lifecycleState = LifecycleState.RESUMING;
        mIsConnecting = true;
        mIsConnected = false;
        return true;
    }

    synchronized boolean markResumed() {
        if (lifecycleState != LifecycleState.RESUMING
                && lifecycleState != LifecycleState.SUSPENDED) return false;
        lifecycleState = LifecycleState.STARTED;
        mIsConnecting = false;
        mIsConnected = true;
        return true;
    }

    synchronized boolean markResumeFailed() {
        if (lifecycleState != LifecycleState.RESUMING) return false;
        lifecycleState = LifecycleState.ENDED;
        mIsConnecting = false;
        mIsConnected = false;
        return true;
    }

    synchronized boolean markSuspended() {
        if (lifecycleState != LifecycleState.STARTED) return false;
        lifecycleState = LifecycleState.SUSPENDED;
        mIsConnecting = false;
        mIsConnected = false;
        return true;
    }

    boolean completeApplicationConnectionSuccess(SessionManagerImpl manager, String receiverSessionId) {
        return manager != null && manager.onSessionStarted(this, receiverSessionId);
    }

    boolean completeApplicationConnectionFailure(SessionManagerImpl manager, int statusCode) {
        return manager != null && manager.onSessionStartFailed(this, statusCode);
    }

    synchronized boolean markStarted(String receiverSessionId) {
        if (lifecycleState != LifecycleState.STARTING) return false;
        lifecycleState = LifecycleState.STARTED;
        mIsConnecting = false;
        mIsConnected = true;
        sessionId = receiverSessionId;
        return true;
    }

    synchronized boolean markStartFailed() {
        if (lifecycleState != LifecycleState.STARTING) return false;
        lifecycleState = LifecycleState.ENDED;
        mIsConnecting = false;
        mIsConnected = false;
        return true;
    }

    synchronized boolean markEnding() {
        if (lifecycleState != LifecycleState.STARTING
                && lifecycleState != LifecycleState.RESUMING
                && lifecycleState != LifecycleState.STARTED
                && lifecycleState != LifecycleState.SUSPENDED) return false;
        lifecycleState = LifecycleState.ENDING;
        mIsConnecting = false;
        mIsConnected = false;
        return true;
    }

    synchronized boolean markEnded() {
        if (lifecycleState == LifecycleState.ENDED) return false;
        lifecycleState = LifecycleState.ENDED;
        mIsConnecting = false;
        mIsConnected = false;
        return true;
    }

    LifecycleState getLifecycleStateForTest() {
        return lifecycleState;
    }

    long getSessionGeneration() {
        return sessionGeneration;
    }

    void bindControllerForTest(String routeId) {
        CastRouteLifecycleRegistry.Snapshot controller =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        this.routeId = routeId;
        this.controllerGeneration = controller.generation;
        this.sessionOwnsController = controller.ownsController;
    }

    private boolean isControllerReleased() {
        return controllerGeneration != 0
                && CastRouteLifecycleRegistry.isReleased(controllerGeneration);
    }

    private static boolean hasCapability(CastDevice castDevice, int capability) {
        return castDevice != null && castDevice.hasCapability(capability);
    }

    private String serviceCategory() {
        if (category == null) return "OTHER";
        if (category.equals(CastMediaControlIntent.CATEGORY_CAST)
                || category.startsWith(CastMediaControlIntent.CATEGORY_CAST + "/")) {
            return "CAST_APP";
        }
        if (category.equals(CastMediaControlIntent.CATEGORY_CAST_REMOTE_PLAYBACK)
                || category.startsWith(CastMediaControlIntent.CATEGORY_CAST_REMOTE_PLAYBACK + "/")) {
            return "REMOTE_PLAYBACK";
        }
        return "OTHER";
    }

    private String lifecycleFields(boolean controllerReleased) {
        return " sessionGeneration=" + sessionGeneration
                + " controllerGeneration=" + controllerGeneration
                + " sessionOwnsController=" + sessionOwnsController
                + " controllerReleased=" + controllerReleased
                + " lifecycleState=" + lifecycleState
                + " startedAfterControllerRelease=" + startCompletionAfterControllerRelease;
    }

    private void clearLocalState() {
        this.routeId = null;
        this.castContext = null;
        this.castDevice = null;
        this.routeInfoExtra = null;
    }

    static void cleanupRoute(IMediaRouter router, String endedRouteId) {
        if (router == null) {
            return;
        }
        boolean targetedCleanupCompleted = false;
        if (endedRouteId != null) {
            try {
                Log.i(TAG, "routeCleanup targetedDisconnect enter routePresent=true");
                // Modern MediaRouter keeps connected routes/controllers separately from the
                // selected route. Targeted disconnect releases both forms for this route only.
                router.disconnectRouteById(endedRouteId);
                targetedCleanupCompleted = true;
                Log.i(TAG, "routeCleanup targetedDisconnect return");
            } catch (RemoteException | RuntimeException ex) {
                Log.w(TAG, "Targeted route cleanup unavailable; using default-route fallback");
            }
        }
        try {
            boolean defaultRouteSelected = router.isDefaultRouteSelected();
            Log.i(TAG, "routeCleanup state targeted=" + targetedCleanupCompleted
                    + " defaultSelected=" + defaultRouteSelected);
            if (!defaultRouteSelected) {
                router.selectDefaultRoute();
                Log.i(TAG, "routeCleanup fallbackDefault return");
            }
        } catch (RemoteException | RuntimeException ex) {
            Log.e(TAG, "Error restoring default route: " + ex.getMessage());
        }
    }

    @Override
    public void notifySessionResumed(boolean wasSuspended) {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        SessionManagerImpl manager = context.getSessionManagerImpl();
        boolean accepted;
        synchronized (manager) {
            accepted = manager.getCurrentSessionForTest() == this && markResumed();
        }
        if (!accepted) {
            Log.w(TAG, "staleSessionResume ignored=true" + lifecycleFields(isControllerReleased()));
            return;
        }
        manager.onSessionResumed(this, wasSuspended);
    }

    @Override
    public void notifyFailedToResumeSession(int error) {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        SessionManagerImpl manager = context.getSessionManagerImpl();
        boolean accepted;
        synchronized (manager) {
            accepted = manager.getCurrentSessionForTest() == this && markResumeFailed();
        }
        if (!accepted) return;
        manager.onSessionResumeFailed(this, error);
        CastRouteLifecycleRegistry.forget(controllerGeneration);
        clearLocalState();
    }

    @Override
    public void notifySessionSuspended(int reason) {
        CastContextImpl context = this.castContext;
        if (context == null) return;
        SessionManagerImpl manager = context.getSessionManagerImpl();
        boolean accepted;
        synchronized (manager) {
            accepted = manager.getCurrentSessionForTest() == this && markSuspended();
        }
        if (!accepted) return;
        manager.onSessionSuspended(this, reason);
    }

    @Override
    public int getSupportedVersion() {
        return org.microg.gms.common.Constants.GMS_VERSION_CODE;
    }

    @Override
    public int getSessionStartType() {
        return startType;
    }

    @Override
    public IObjectWrapper getWrappedObject() {
        return ObjectWrapper.wrap(this);
    }
}
