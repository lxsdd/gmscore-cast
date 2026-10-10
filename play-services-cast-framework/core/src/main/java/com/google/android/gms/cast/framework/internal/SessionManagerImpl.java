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

import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.ICastStateListener;
import com.google.android.gms.cast.framework.ISession;
import com.google.android.gms.cast.framework.ISessionManager;
import com.google.android.gms.cast.framework.ISessionManagerListener;
import com.google.android.gms.cast.framework.internal.CastContextImpl;
import com.google.android.gms.cast.framework.internal.SessionImpl;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import java.util.Set;
import java.util.HashSet;

public class SessionManagerImpl extends ISessionManager.Stub {
    private static final String TAG = SessionManagerImpl.class.getSimpleName();

    private CastContextImpl castContext;

    private Set<ISessionManagerListener> sessionManagerListeners = new HashSet<ISessionManagerListener>();
    private Set<ICastStateListener> castStateListeners = new HashSet<ICastStateListener>();

    private SessionImpl currentSession;

    private int castState = CastState.NO_DEVICES_AVAILABLE;

    public SessionManagerImpl(CastContextImpl castContext) {
        this.castContext = castContext;
    }

    @Override
    public IObjectWrapper getWrappedCurrentSession() throws RemoteException {
        if (this.currentSession == null) {
            return ObjectWrapper.wrap(null);
        }
        return this.currentSession.getWrappedSession();
    }

    @Override
    public void endCurrentSession(boolean b, boolean stopCasting) throws RemoteException {
        Log.i(TAG, "endCurrentSession stopCasting=" + stopCasting
                + " currentSessionPresent=" + (this.currentSession != null));
        if (this.currentSession == null) {
            return;
        }

        SessionImpl session = this.currentSession;
        session.end(stopCasting, this);
    }

    @Override
    public void addSessionManagerListener(ISessionManagerListener listener) {
        Log.i(TAG, "addSessionManagerListener callbackClass=" + getSessionListenerClass(listener)
                + " supportedVersion=" + getSessionListenerVersion(listener));
        this.sessionManagerListeners.add(listener);
    }

    @Override
    public void removeSessionManagerListener(ISessionManagerListener listener) {
        Log.d(TAG, "unimplemented Method: removeSessionManagerListener");
        this.sessionManagerListeners.remove(listener);
    }

    @Override
    public void addCastStateListener(ICastStateListener listener) {
        Log.d(TAG, "unimplemented Method: addCastStateListener");
        this.castStateListeners.add(listener);
    }

    @Override
    public void removeCastStateListener(ICastStateListener listener) {
        Log.d(TAG, "unimplemented Method: removeCastStateListener");
        this.castStateListeners.remove(listener);
    }

    @Override
    public IObjectWrapper getWrappedThis() throws RemoteException {
        return ObjectWrapper.wrap(this);
    }

    @Override
    public int getCastState() {
        return this.castState;
    }

    @Override
    public void startSession(Bundle params) {
        if (params == null) return;
        String routeId = params.getString("CAST_INTENT_TO_CAST_ROUTE_ID_KEY");
        if (routeId == null) return;
        try {
            castContext.getRouter().selectRouteById(routeId);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to select requested route: " + e.getMessage());
        }
    }

    public void onRouteConnectionLost(String routeId, int reason) {
        SessionImpl session;
        synchronized (this) {
            session = currentSession;
        }
        if (session == null || routeId == null || !routeId.equals(session.getRouteId())) return;
        session.notifySessionSuspended(reason);
    }

    public void onRouteChanged(String routeId, Bundle extras) {
        SessionImpl session;
        synchronized (this) {
            session = currentSession;
        }
        if (session == null || routeId == null || !routeId.equals(session.getRouteId())) return;
        session.onRouteInfoUpdated(extras);
    }

    public void onRouteUnselected(String routeId, boolean stopCasting) {
        SessionImpl session;
        synchronized (this) {
            session = currentSession;
        }
        if (session == null || routeId == null || !routeId.equals(session.getRouteId())) return;
        try {
            session.end(stopCasting);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to end unselected route session: " + e.getMessage());
        }
    }

    private void setCastState(int castState) {
        this.castState = castState;
        this.onCastStateChanged();
    }

    public void onCastStateChanged() {
        for (ICastStateListener listener : new HashSet<>(this.castStateListeners)) {
            try {
                listener.onCastStateChanged(this.castState);
            } catch (RemoteException | RuntimeException e) {
                Log.d(TAG, "Remote exception calling onCastStateChanged: " + e.getMessage());
            }
        }
    }

    public boolean onSessionStarting(SessionImpl session) {
        boolean accepted;
        synchronized (this) {
            boolean currentAvailable = this.currentSession == null || this.currentSession == session;
            if (!currentAvailable) {
                accepted = false;
            } else if (session.getLifecycleStateForTest() == SessionImpl.LifecycleState.NEW) {
                // Keep the established direct-manager test/internal contract while production
                // SessionImpl.start() pre-marks STARTING before its preparation callbacks.
                accepted = session.markStarting();
            } else {
                accepted = session.getLifecycleStateForTest() == SessionImpl.LifecycleState.STARTING;
            }
            if (accepted) this.currentSession = session;
        }
        Log.i(TAG, "onSessionStarting sessionGeneration=" + session.getSessionGeneration()
                + " accepted=" + accepted + " sessionStillCurrent="
                + (this.currentSession == session)
                + " listenerCount=" + this.sessionManagerListeners.size());
        if (!accepted) return false;
        this.setCastState(CastState.CONNECTING);
        int callbackIndex = 0;
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionStarting(session.getSessionProxy().getWrappedSession());
                Log.i(TAG, "onSessionStartingCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=true");
            } catch (RemoteException e) {
                Log.i(TAG, "onSessionStartingCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=false");
                Log.d(TAG, "Remote exception calling onSessionStarting: " + e.getMessage());
            }
            callbackIndex++;
        }
        return true;
    }

    public boolean onSessionStartFailed(SessionImpl session, int error) {
        boolean accepted;
        synchronized (this) {
            accepted = this.currentSession == session && session.markStartFailed();
            if (accepted) this.currentSession = null;
        }
        Log.i(TAG, "onSessionStartFailed sessionGeneration=" + session.getSessionGeneration()
                + " accepted=" + accepted + " error=" + error);
        if (!accepted) return false;
        this.setCastState(CastState.NOT_CONNECTED);
        int deliveredCount = 0;
        for (ISessionManagerListener listener : new HashSet<>(this.sessionManagerListeners)) {
            try {
                listener.onSessionStartFailed(session.getSessionProxy().getWrappedSession(), error);
                deliveredCount++;
            } catch (RemoteException | RuntimeException e) {
                Log.d(TAG, "Remote exception calling onSessionStartFailed: " + e.getMessage());
            }
        }
        Log.i(TAG, "FAILURE_LIFECYCLE: stage=LISTENER_PROPAGATION statusCode=" + error
                + " accepted=true listenerCount=" + this.sessionManagerListeners.size()
                + " deliveredCount=" + deliveredCount);
        return true;
    }

    public boolean onSessionStarted(SessionImpl session, String sessionId) {
        boolean accepted;
        synchronized (this) {
            accepted = this.currentSession == session && session.markStarted(sessionId);
        }
        Log.i(TAG, "onSessionStarted sessionGeneration=" + session.getSessionGeneration()
                + " accepted=" + accepted + " sessionStillCurrent="
                + (this.currentSession == session)
                + " listenerCount=" + this.sessionManagerListeners.size()
                + " sessionIdPresent=" + (sessionId != null));
        if (!accepted) return false;
        this.setCastState(CastState.CONNECTED);
        int callbackIndex = 0;
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionStarted(session.getSessionProxy().getWrappedSession(), sessionId);
                Log.i(TAG, "onSessionStartedCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=true");
            } catch (RemoteException e) {
                Log.i(TAG, "onSessionStartedCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=false");
                Log.d(TAG, "Remote exception calling onSessionStarted: " + e.getMessage());
            }
            callbackIndex++;
        }
        return true;
    }

    private String getSessionListenerClass(ISessionManagerListener listener) {
        if (listener == null) {
            return "<null>";
        }
        try {
            Object wrapped = ObjectWrapper.unwrap(listener.getWrappedThis());
            return wrapped == null ? "<null>" : wrapped.getClass().getName();
        } catch (RemoteException | RuntimeException e) {
            return "<unavailable>";
        }
    }

    private int getSessionListenerVersion(ISessionManagerListener listener) {
        if (listener == null) {
            return -1;
        }
        try {
            return listener.getSupportedVersion();
        } catch (RemoteException | RuntimeException e) {
            return -1;
        }
    }

    public void onSessionResumed(SessionImpl session, boolean wasSuspended) {
        synchronized (this) {
            if (this.currentSession != session) return;
        }
        this.setCastState(CastState.CONNECTED);
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionResumed(session.getSessionProxy().getWrappedSession(), wasSuspended);
            } catch (RemoteException e) {
                Log.d(TAG, "Remote exception calling onSessionResumed: " + e.getMessage());
            }
        }
    }

    public boolean onSessionEnding(SessionImpl session) {
        boolean accepted;
        synchronized (this) {
            accepted = this.currentSession == session && session.markEnding();
        }
        Log.i(TAG, "onSessionEnding sessionGeneration=" + session.getSessionGeneration()
                + " accepted=" + accepted + " sessionStillCurrent="
                + (this.currentSession == session)
                + " listenerCount=" + this.sessionManagerListeners.size());
        if (!accepted) return false;
        int callbackIndex = 0;
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionEnding(session.getSessionProxy().getWrappedSession());
                Log.i(TAG, "onSessionEndingCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=true");
            } catch (RemoteException | RuntimeException e) {
                Log.i(TAG, "onSessionEndingCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=false");
                Log.d(TAG, "Remote exception calling onSessionEnding: " + e.getMessage());
            }
            callbackIndex++;
        }
        return true;
    }

    public boolean onSessionEnded(SessionImpl session, int error) {
        boolean matchingCurrentSession;
        boolean firstEnd;
        synchronized (this) {
            matchingCurrentSession = this.currentSession == session;
            firstEnd = session.markEnded();
            if (matchingCurrentSession && firstEnd) this.currentSession = null;
        }
        boolean deliver = matchingCurrentSession && firstEnd;
        if (deliver) this.setCastState(CastState.NOT_CONNECTED);
        Log.i(TAG, "onSessionEnded sessionGeneration=" + session.getSessionGeneration()
                + " currentSessionMatched=" + matchingCurrentSession
                + " firstEnd=" + firstEnd + " listenerDelivery=" + deliver
                + " currentSessionPresentAfter=" + (this.currentSession != null) + " listenerCount="
                + this.sessionManagerListeners.size());
        if (!deliver) return false;
        int callbackIndex = 0;
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionEnded(session.getSessionProxy().getWrappedSession(), error);
                Log.i(TAG, "onSessionEndedCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=true");
            } catch (RemoteException | RuntimeException e) {
                Log.i(TAG, "onSessionEndedCallback index=" + callbackIndex
                        + " callbackClass=" + getSessionListenerClass(listener) + " delivered=false");
                Log.d(TAG, "Remote exception calling onSessionEnded: " + e.getMessage());
            }
            callbackIndex++;
        }
        return true;
    }

    SessionImpl getCurrentSessionForTest() {
        return this.currentSession;
    }

    public synchronized SessionImpl getCurrentSession() {
        return this.currentSession;
    }

    public void onSessionResuming(SessionImpl session, String sessionId) {
        synchronized (this) {
            if (this.currentSession == null) this.currentSession = session;
            if (this.currentSession != session) return;
        }
        this.setCastState(CastState.CONNECTING);
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionResuming(session.getSessionProxy().getWrappedSession(), sessionId);
            } catch (RemoteException e) {
                Log.d(TAG, "Remote exception calling onSessionResuming: " + e.getMessage());
            }
        }
    }

    public void onSessionResumeFailed(SessionImpl session, int error) {
        synchronized (this) {
            if (this.currentSession != session) return;
            this.currentSession = null;
        }
        this.setCastState(CastState.NOT_CONNECTED);
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionResumeFailed(session.getSessionProxy().getWrappedSession(), error);
            } catch (RemoteException e) {
                Log.d(TAG, "Remote exception calling onSessionResumeFailed: " + e.getMessage());
            }
        }
    }

    public void onSessionSuspended(SessionImpl session, int reason) {
        synchronized (this) {
            if (this.currentSession != session) return;
        }
        this.setCastState(CastState.NOT_CONNECTED);
        for (ISessionManagerListener listener : this.sessionManagerListeners) {
            try {
                listener.onSessionSuspended(session.getSessionProxy().getWrappedSession(), reason);
            } catch (RemoteException e) {
                Log.d(TAG, "Remote exception calling onSessionSuspended: " + e.getMessage());
            }
        }
    }
}
