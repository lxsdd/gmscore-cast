/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.cast.internal;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local correlation for Cast route controllers and framework sessions.
 *
 * Route IDs are retained only as internal map keys and are never exposed or logged.
 */
public final class CastRouteLifecycleRegistry {
    public static final int CONNECTION_STATE_DISCONNECTED = 0;
    public static final int CONNECTION_STATE_CONNECTING = 1;
    public static final int CONNECTION_STATE_CONNECTED = 2;

    private static final AtomicLong NEXT_CONTROLLER_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<String, Long> LATEST_BY_ROUTE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, ControllerState> CONTROLLERS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private CastRouteLifecycleRegistry() {
    }

    public static long controllerCreated(String routeId) {
        long generation = NEXT_CONTROLLER_GENERATION.incrementAndGet();
        ControllerState state = new ControllerState(routeId);
        CONTROLLERS.put(generation, state);
        if (routeId != null) {
            while (true) {
                Long previous = LATEST_BY_ROUTE.get(routeId);
                if (previous == null) {
                    if (LATEST_BY_ROUTE.putIfAbsent(routeId, generation) == null) break;
                    continue;
                }
                ControllerState previousState = CONTROLLERS.get(previous);
                if (previousState == null) {
                    if (LATEST_BY_ROUTE.replace(routeId, previous, generation)) break;
                    continue;
                }
                boolean connectionStateChanged;
                synchronized (previousState) {
                    if (!previous.equals(LATEST_BY_ROUTE.get(routeId))) continue;
                    LATEST_BY_ROUTE.put(routeId, generation);
                    connectionStateChanged = previousState.connectionState
                            != CONNECTION_STATE_DISCONNECTED;
                    if (previousState.released) CONTROLLERS.remove(previous, previousState);
                }
                if (connectionStateChanged) notifyConnectionStateChanged(
                        routeId, previous, CONNECTION_STATE_DISCONNECTED);
                break;
            }
        }
        return generation;
    }

    public static void controllerReleased(long generation) {
        ControllerState state = CONTROLLERS.get(generation);
        if (state == null) return;
        boolean changed = false;
        synchronized (state) {
            if (!state.released) {
                state.released = true;
                if (isLatest(state.routeId, generation)
                        && state.connectionState != CONNECTION_STATE_DISCONNECTED) {
                    state.connectionState = CONNECTION_STATE_DISCONNECTED;
                    changed = true;
                }
            }
        }
        if (changed) notifyConnectionStateChanged(
                state.routeId, generation, CONNECTION_STATE_DISCONNECTED);
    }

    public static boolean markConnecting(long generation) {
        return transition(generation, CONNECTION_STATE_CONNECTING);
    }

    public static boolean markConnected(long generation) {
        return transition(generation, CONNECTION_STATE_CONNECTED);
    }

    public static boolean markDisconnected(long generation) {
        return transition(generation, CONNECTION_STATE_DISCONNECTED);
    }

    public static void routeLost(String routeId) {
        Long generation = routeId == null ? null : LATEST_BY_ROUTE.get(routeId);
        if (generation != null) transition(generation, CONNECTION_STATE_DISCONNECTED);
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(new WeakReference<>(listener));
    }

    public static void removeListener(Listener listener) {
        for (WeakReference<Listener> reference : LISTENERS) {
            Listener registered = reference.get();
            if (registered == null || registered == listener) LISTENERS.remove(reference);
        }
    }

    public static Snapshot snapshotForRoute(String routeId) {
        Long generation = routeId == null ? null : LATEST_BY_ROUTE.get(routeId);
        ControllerState state = generation == null ? null : CONTROLLERS.get(generation);
        if (state == null) {
            return new Snapshot(0, false, false, CONNECTION_STATE_DISCONNECTED);
        }
        synchronized (state) {
            if (!generation.equals(LATEST_BY_ROUTE.get(routeId))) {
                return new Snapshot(0, false, false, CONNECTION_STATE_DISCONNECTED);
            }
            return new Snapshot(generation, true, state.released, state.connectionState);
        }
    }

    /**
     * Runs one route-bound operation while replacement, disconnect and release transitions for
     * the same controller are excluded. The operation must stay narrow and must not call back
     * into lifecycle mutation methods.
     */
    public static boolean runIfConnected(String routeId, long generation,
                                         ConnectedControllerOperation operation) {
        if (routeId == null || generation == 0 || operation == null) return false;
        ControllerState state = CONTROLLERS.get(generation);
        if (state == null || !routeId.equals(state.routeId)) return false;
        synchronized (state) {
            if (CONTROLLERS.get(generation) != state || state.released
                    || !isLatest(routeId, generation)
                    || state.connectionState != CONNECTION_STATE_CONNECTED) return false;
            return operation.run();
        }
    }

    public static boolean isReleased(long generation) {
        ControllerState state = CONTROLLERS.get(generation);
        return state != null && state.released;
    }

    public static void forget(long generation) {
        if (generation == 0) return;
        ControllerState state = CONTROLLERS.get(generation);
        if (state != null) {
            boolean notify = false;
            synchronized (state) {
                if (!CONTROLLERS.remove(generation, state)) return;
                if (state.routeId != null) {
                    boolean wasLatest = LATEST_BY_ROUTE.remove(state.routeId, generation);
                    notify = wasLatest
                            && state.connectionState != CONNECTION_STATE_DISCONNECTED;
                }
            }
            if (notify) notifyConnectionStateChanged(
                    state.routeId, generation, CONNECTION_STATE_DISCONNECTED);
        }
    }

    private static boolean transition(long generation, int connectionState) {
        ControllerState state = CONTROLLERS.get(generation);
        if (state == null || !isLatest(state.routeId, generation)) return false;
        synchronized (state) {
            if (state.released || !isLatest(state.routeId, generation)
                    || state.connectionState == connectionState) return false;
            state.connectionState = connectionState;
        }
        notifyConnectionStateChanged(state.routeId, generation, connectionState);
        return true;
    }

    private static boolean isLatest(String routeId, long generation) {
        return routeId != null && Long.valueOf(generation).equals(LATEST_BY_ROUTE.get(routeId));
    }

    private static void notifyConnectionStateChanged(String routeId, long generation,
                                                     int connectionState) {
        if (routeId == null) return;
        for (WeakReference<Listener> reference : LISTENERS) {
            Listener listener = reference.get();
            if (listener == null) {
                LISTENERS.remove(reference);
            } else {
                listener.onRouteConnectionStateChanged(routeId, generation, connectionState);
            }
        }
    }

    public interface Listener {
        void onRouteConnectionStateChanged(String routeId, long generation, int connectionState);
    }

    public interface ConnectedControllerOperation {
        boolean run();
    }

    private static final class ControllerState {
        final String routeId;
        volatile boolean released;
        volatile int connectionState = CONNECTION_STATE_DISCONNECTED;

        ControllerState(String routeId) {
            this.routeId = routeId;
        }
    }

    public static final class Snapshot {
        public final long generation;
        public final boolean ownsController;
        public final boolean released;
        public final int connectionState;

        Snapshot(long generation, boolean ownsController, boolean released, int connectionState) {
            this.generation = generation;
            this.ownsController = ownsController;
            this.released = released;
            this.connectionState = connectionState;
        }
    }
}
