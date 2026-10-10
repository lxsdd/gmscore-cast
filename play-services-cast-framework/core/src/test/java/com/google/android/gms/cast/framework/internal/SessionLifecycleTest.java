package com.google.android.gms.cast.framework.internal;

import android.os.IBinder;

import com.google.android.gms.cast.framework.ISessionManagerListener;
import com.google.android.gms.cast.framework.ISessionProxy;
import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SessionLifecycleTest {
    @Test
    public void endingStartingSessionRejectsLateStartSuccess() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(manager.onSessionEnding(session));
        assertTrue(manager.onSessionEnded(session, 0));

        assertFalse(session.completeApplicationConnectionSuccess(manager, "late"));
        assertEquals(SessionImpl.LifecycleState.ENDED, session.getLifecycleStateForTest());
        assertNull(manager.getCurrentSessionForTest());
    }

    @Test
    public void lateGenerationOneCallbacksCannotAffectGenerationTwo() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl first = session(new ArrayList<Boolean>());
        SessionImpl second = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(first));
        assertTrue(manager.onSessionEnding(first));
        assertTrue(manager.onSessionEnded(first, 0));
        assertTrue(manager.onSessionStarting(second));

        assertFalse(first.completeApplicationConnectionSuccess(manager, "late-first"));
        assertFalse(manager.onSessionEnded(first, 0));
        assertSame(second, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTING, second.getLifecycleStateForTest());
        assertTrue(second.getSessionGeneration() > first.getSessionGeneration());
    }

    @Test
    public void releasedControllerAndLateCompletionDoNotCreateZombieState() {
        String routeId = "test-route";
        long controllerGeneration = CastRouteLifecycleRegistry.controllerCreated(routeId);
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(new ArrayList<Boolean>());
        session.bindControllerForTest(routeId);

        assertTrue(manager.onSessionStarting(session));
        CastRouteLifecycleRegistry.controllerReleased(controllerGeneration);
        assertTrue(manager.onSessionEnding(session));
        assertTrue(manager.onSessionEnded(session, 0));

        assertFalse(session.completeApplicationConnectionSuccess(manager, "late"));
        assertNull(manager.getCurrentSessionForTest());
        CastRouteLifecycleRegistry.forget(controllerGeneration);
    }

    @Test
    public void duplicateEndDeliversListenerExactlyOnce() {
        List<String> callbacks = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        manager.addSessionManagerListener(sessionListener(callbacks));
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(manager.onSessionEnding(session));
        assertTrue(manager.onSessionEnded(session, 0));
        assertFalse(manager.onSessionEnded(session, 0));

        assertEquals(1, count(callbacks, "ended"));
    }

    @Test
    public void successfulStartDoesNotIssueFrameworkReselection() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));

        assertSame(session, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTED, session.getLifecycleStateForTest());
    }

    @Test
    public void startFailureIsTerminalAndDeliveredExactlyOnce() {
        List<String> callbacks = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        manager.addSessionManagerListener(sessionListener(callbacks));
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionFailure(manager, 7));
        assertFalse(session.completeApplicationConnectionFailure(manager, 7));

        assertEquals(SessionImpl.LifecycleState.ENDED, session.getLifecycleStateForTest());
        assertNull(manager.getCurrentSessionForTest());
        assertEquals(1, count(callbacks, "startFailed:7"));
    }

    @Test
    public void lateStartFailureCannotTerminateSuccessfulTvSession() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));
        assertFalse(session.completeApplicationConnectionFailure(manager, 7));

        assertSame(session, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTED, session.getLifecycleStateForTest());
        assertTrue(session.isConnected());
    }

    @Test
    public void failedGenerationCannotAffectNextStartingSession() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl first = session(new ArrayList<Boolean>());
        SessionImpl second = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(first));
        assertTrue(first.completeApplicationConnectionFailure(manager, 7));
        assertTrue(manager.onSessionStarting(second));
        assertFalse(first.completeApplicationConnectionFailure(manager, 7));

        assertSame(second, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTING, second.getLifecycleStateForTest());
    }

    @Test
    public void failureAfterNormalEndIsRejected() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));
        assertTrue(manager.onSessionEnding(session));
        assertTrue(manager.onSessionEnded(session, 0));
        assertFalse(session.completeApplicationConnectionFailure(manager, 7));

        assertNull(manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.ENDED, session.getLifecycleStateForTest());
    }

    @Test
    public void contextlessFailureIsAnIdempotentNoOp() {
        SessionImpl session = session(new ArrayList<Boolean>());

        assertFalse(session.onApplicationConnectionFailure(7));
        assertFalse(session.onApplicationConnectionFailure(7));

        assertEquals(SessionImpl.LifecycleState.NEW, session.getLifecycleStateForTest());
    }

    @Test
    public void throwingFailureListenerDoesNotBlockOtherListenersOrCleanup() {
        List<String> callbacks = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        manager.addSessionManagerListener(throwingStartFailureListener());
        manager.addSessionManagerListener(sessionListener(callbacks));
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionFailure(manager, 7));

        assertEquals(1, count(callbacks, "startFailed:7"));
        assertEquals(SessionImpl.LifecycleState.ENDED, session.getLifecycleStateForTest());
        assertNull(manager.getCurrentSessionForTest());
    }

    @Test
    public void laterLegitimateSelectionStartsNewGenerationNormally() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl first = session(new ArrayList<Boolean>());
        assertTrue(manager.onSessionStarting(first));
        assertTrue(first.completeApplicationConnectionSuccess(manager, "first"));
        assertTrue(manager.onSessionEnding(first));
        assertTrue(manager.onSessionEnded(first, 0));

        SessionImpl second = session(new ArrayList<Boolean>());
        assertTrue(manager.onSessionStarting(second));
        assertTrue(second.completeApplicationConnectionSuccess(manager, "second"));

        assertSame(second, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTED, second.getLifecycleStateForTest());
    }

    @Test
    public void leaveSemanticsAreForwardedUnchanged() throws Exception {
        List<Boolean> endValues = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(endValues);
        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));

        manager.endCurrentSession(false, false);

        assertEquals(1, endValues.size());
        assertFalse(endValues.get(0));
    }

    @Test
    public void stopCastingSemanticsAreForwardedExactlyOnce() throws Exception {
        List<Boolean> endValues = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl session = session(endValues);
        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));

        manager.endCurrentSession(false, true);
        manager.endCurrentSession(false, true);

        assertEquals(1, endValues.size());
        assertTrue(endValues.get(0));
    }

    @Test
    public void suspendedSessionRemainsCurrentAndCanResume() {
        List<String> callbacks = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        manager.addSessionManagerListener(sessionListener(callbacks));
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));
        assertTrue(session.markSuspended());
        manager.onSessionSuspended(session, 1);

        assertSame(session, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.SUSPENDED, session.getLifecycleStateForTest());
        assertFalse(session.isConnected());
        assertEquals(1, count(callbacks, "suspended:1"));

        assertTrue(session.markResuming());
        manager.onSessionResuming(session, "receiver-session");
        assertSame(session, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.RESUMING, session.getLifecycleStateForTest());
        assertEquals(1, count(callbacks, "resuming:receiver-session"));

        assertTrue(session.markResumed());
        manager.onSessionResumed(session, true);

        assertSame(session, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTED, session.getLifecycleStateForTest());
        assertTrue(session.isConnected());
        assertEquals(1, count(callbacks, "resumed:true"));
    }

    @Test
    public void staleSuspensionCannotAffectNewerCurrentSession() {
        SessionManagerImpl manager = new SessionManagerImpl(null);
        SessionImpl first = session(new ArrayList<Boolean>());
        SessionImpl second = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(first));
        assertTrue(first.completeApplicationConnectionSuccess(manager, "first"));
        assertTrue(manager.onSessionEnding(first));
        assertTrue(manager.onSessionEnded(first, 0));
        assertTrue(manager.onSessionStarting(second));
        assertTrue(second.completeApplicationConnectionSuccess(manager, "second"));

        // A late suspension from the already-ended predecessor must be ignored without
        // changing either session's state.
        manager.onSessionSuspended(first, 1);

        assertSame(second, manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.STARTED, second.getLifecycleStateForTest());
        assertTrue(second.isConnected());
    }

    @Test
    public void resumeFailureOnlyClearsMatchingCurrentSession() {
        List<String> callbacks = new ArrayList<>();
        SessionManagerImpl manager = new SessionManagerImpl(null);
        manager.addSessionManagerListener(sessionListener(callbacks));
        SessionImpl session = session(new ArrayList<Boolean>());

        assertTrue(manager.onSessionStarting(session));
        assertTrue(session.completeApplicationConnectionSuccess(manager, "receiver-session"));
        assertTrue(session.markSuspended());
        manager.onSessionSuspended(session, 1);
        assertTrue(session.markResuming());
        manager.onSessionResuming(session, "receiver-session");
        assertTrue(session.markResumeFailed());

        manager.onSessionResumeFailed(session, 7);

        assertNull(manager.getCurrentSessionForTest());
        assertEquals(SessionImpl.LifecycleState.ENDED, session.getLifecycleStateForTest());
        assertEquals(1, count(callbacks, "resumeFailed:7"));
    }

    @Test
    public void routeCleanupTargetsEachEndedRouteAndRestoresFallback() {
        List<String> calls = new ArrayList<>();
        IMediaRouter router = mediaRouter(calls, false);

        SessionImpl.cleanupRoute(router, "route-one");
        SessionImpl.cleanupRoute(router, "route-two");

        assertEquals(2, count(calls, "disconnect"));
        assertEquals(2, count(calls, "default"));
    }

    private static SessionImpl session(List<Boolean> endValues) {
        return new SessionImpl("category", null, sessionProxy(endValues));
    }

    private static int count(List<String> calls, String value) {
        int count = 0;
        for (String call : calls) if (value.equals(call)) count++;
        return count;
    }

    private static ISessionManagerListener sessionListener(List<String> callbacks) {
        return (ISessionManagerListener) Proxy.newProxyInstance(
                SessionLifecycleTest.class.getClassLoader(),
                new Class<?>[]{ISessionManagerListener.class},
                (proxy, method, args) -> {
                    if ("onSessionEnded".equals(method.getName())) callbacks.add("ended");
                    if ("onSessionStartFailed".equals(method.getName())) {
                        callbacks.add("startFailed:" + args[1]);
                    }
                    if ("onSessionSuspended".equals(method.getName())) {
                        callbacks.add("suspended:" + args[1]);
                    }
                    if ("onSessionResuming".equals(method.getName())) {
                        callbacks.add("resuming:" + args[1]);
                    }
                    if ("onSessionResumed".equals(method.getName())) {
                        callbacks.add("resumed:" + args[1]);
                    }
                    if ("onSessionResumeFailed".equals(method.getName())) {
                        callbacks.add("resumeFailed:" + args[1]);
                    }
                    if ("getSupportedVersion".equals(method.getName())) return 0;
                    if ("asBinder".equals(method.getName())) return (IBinder) null;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private static ISessionManagerListener throwingStartFailureListener() {
        return (ISessionManagerListener) Proxy.newProxyInstance(
                SessionLifecycleTest.class.getClassLoader(),
                new Class<?>[]{ISessionManagerListener.class},
                (proxy, method, args) -> {
                    if ("onSessionStartFailed".equals(method.getName())) {
                        throw new IllegalStateException("listener failure");
                    }
                    if ("getSupportedVersion".equals(method.getName())) return 0;
                    if ("asBinder".equals(method.getName())) return (IBinder) null;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private static ISessionProxy sessionProxy(List<Boolean> endValues) {
        return (ISessionProxy) Proxy.newProxyInstance(
                SessionLifecycleTest.class.getClassLoader(),
                new Class<?>[]{ISessionProxy.class},
                (proxy, method, args) -> {
                    if ("end".equals(method.getName())) endValues.add((Boolean) args[0]);
                    if ("getWrappedSession".equals(method.getName())) return null;
                    if ("asBinder".equals(method.getName())) return (IBinder) null;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private static IMediaRouter mediaRouter(List<String> calls, boolean defaultSelected) {
        return (IMediaRouter) Proxy.newProxyInstance(
                SessionLifecycleTest.class.getClassLoader(),
                new Class<?>[]{IMediaRouter.class},
                (proxy, method, args) -> {
                    if ("disconnectRouteById".equals(method.getName())) calls.add("disconnect");
                    if ("selectDefaultRoute".equals(method.getName())) calls.add("default");
                    if ("isDefaultRouteSelected".equals(method.getName())) return defaultSelected;
                    if ("asBinder".equals(method.getName())) return (IBinder) null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }
}
