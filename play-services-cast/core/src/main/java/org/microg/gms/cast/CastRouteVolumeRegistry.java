/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import android.util.Log;

import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local bridge from a MediaRouter route controller to the already connected CastV2
 * transport owned by {@link CastDeviceControllerImpl}.
 *
 * <p>The route ID is used only as an internal key. Every operation also checks the route
 * controller generation, so an old controller or late transport teardown cannot affect its
 * replacement.</p>
 */
final class CastRouteVolumeRegistry {
    interface Listener {
        void onRouteVolumeChanged(String routeId);
    }

    interface Transport {
        void setVolume(float volume) throws Exception;
        void setVolumeByIncrement(float increment) throws Exception;
        void setMuted(boolean muted) throws Exception;
        boolean isConnected();
    }

    static final int MAX_ROUTE_VOLUME = 20;
    static final int DEFAULT_ROUTE_VOLUME = MAX_ROUTE_VOLUME / 2;

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final CastRouteLifecycleRegistry.Listener LIFECYCLE_LISTENER =
            (routeId, generation, connectionState) -> {
        Entry entry = routeId == null ? null : ENTRIES.get(routeId);
        if (entry != null && entry.generation == generation
                && connectionState != CastRouteLifecycleRegistry.CONNECTION_STATE_CONNECTED) {
            entry.invalidateReceiverState();
        }
    };

    static {
        CastRouteLifecycleRegistry.addListener(LIFECYCLE_LISTENER);
    }

    private CastRouteVolumeRegistry() {
    }

    static boolean register(String routeId, long generation, Transport transport) {
        if (routeId == null || generation == 0 || transport == null || !isCurrent(routeId, generation)) {
            return false;
        }
        Entry previous = ENTRIES.get(routeId);
        Entry entry = new Entry(generation, transport,
                previous == null ? DEFAULT_ROUTE_VOLUME : previous.currentVolume);
        while (true) {
            previous = ENTRIES.get(routeId);
            if (previous == null) {
                if (ENTRIES.putIfAbsent(routeId, entry) == null) break;
                continue;
            }
            synchronized (previous) {
                if (ENTRIES.get(routeId) != previous) continue;
                ENTRIES.put(routeId, entry);
                previous.deactivate();
                break;
            }
        }
        if (!isCurrent(routeId, generation)) {
            synchronized (entry) {
                ENTRIES.remove(routeId, entry);
                entry.deactivate();
            }
            return false;
        }
        return true;
    }

    static void unregister(String routeId, long generation, Transport transport) {
        if (routeId == null || generation == 0 || transport == null) return;
        Entry entry = ENTRIES.get(routeId);
        if (entry == null || entry.generation != generation || entry.transport != transport) return;
        synchronized (entry) {
            if (ENTRIES.get(routeId) != entry) return;
            if (ENTRIES.remove(routeId, entry)) entry.deactivate();
        }
    }

    static boolean setVolume(String routeId, long generation, int requestedVolume) {
        Entry entry = currentEntry(routeId, generation);
        int volume = clamp(requestedVolume);
        boolean applied = entry != null
                && entry.setVolume(volume, volume / (float) MAX_ROUTE_VOLUME);
        if (applied) notifyVolumeChanged(routeId);
        return applied;
    }

    static boolean updateVolume(String routeId, long generation, int delta) {
        Entry entry = currentEntry(routeId, generation);
        float increment = Math.max(-MAX_ROUTE_VOLUME, Math.min(MAX_ROUTE_VOLUME, delta))
                / (float) MAX_ROUTE_VOLUME;
        boolean applied = entry != null && entry.updateVolume(delta, increment);
        if (applied) notifyVolumeChanged(routeId);
        return applied;
    }

    static boolean setMuted(String routeId, long generation, boolean muted) {
        return CastRouteLifecycleRegistry.runIfConnected(routeId, generation, () -> {
            Entry entry = ENTRIES.get(routeId);
            return entry != null && entry.setMuted(routeId, generation, muted);
        });
    }

    static boolean toggleMuted(String routeId, long generation) {
        return CastRouteLifecycleRegistry.runIfConnected(routeId, generation, () -> {
            Entry entry = ENTRIES.get(routeId);
            return entry != null && entry.toggleMuted(routeId, generation);
        });
    }

    static void updateFromReceiver(String routeId, long generation, double level,
                                   boolean muted) {
        Entry entry = currentEntry(routeId, generation);
        if (entry == null || !Double.isFinite(level)) return;
        int volume = clamp((int) Math.round(level * MAX_ROUTE_VOLUME));
        if (entry.updateFromReceiver(volume, muted)) notifyVolumeChanged(routeId);
    }

    static int volumeForRoute(String routeId) {
        CastRouteLifecycleRegistry.Snapshot snapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        Entry entry = routeId == null ? null : ENTRIES.get(routeId);
        return entry != null && snapshot.ownsController && !snapshot.released
                && entry.generation == snapshot.generation
                ? entry.currentVolume : DEFAULT_ROUTE_VOLUME;
    }

    static boolean mutedForRoute(String routeId) {
        CastRouteLifecycleRegistry.Snapshot snapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        Entry entry = routeId == null ? null : ENTRIES.get(routeId);
        return entry != null && snapshot.ownsController && !snapshot.released
                && entry.generation == snapshot.generation && entry.currentMuted;
    }

    static boolean muteStateKnownForRoute(String routeId) {
        CastRouteLifecycleRegistry.Snapshot snapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        Entry entry = routeId == null ? null : ENTRIES.get(routeId);
        return entry != null && snapshot.ownsController && !snapshot.released
                && entry.generation == snapshot.generation && entry.currentMutedKnown;
    }

    static boolean togglePendingForRoute(String routeId) {
        CastRouteLifecycleRegistry.Snapshot snapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        Entry entry = routeId == null ? null : ENTRIES.get(routeId);
        return entry != null && snapshot.ownsController && !snapshot.released
                && entry.generation == snapshot.generation && entry.togglePending;
    }

    static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(new WeakReference<>(listener));
    }

    private static void notifyVolumeChanged(String routeId) {
        for (WeakReference<Listener> reference : LISTENERS) {
            Listener listener = reference.get();
            if (listener == null) LISTENERS.remove(reference);
            else listener.onRouteVolumeChanged(routeId);
        }
    }

    private static Entry currentEntry(String routeId, long generation) {
        if (routeId == null || generation == 0 || !isCurrent(routeId, generation)) return null;
        Entry entry = ENTRIES.get(routeId);
        return entry != null && entry.generation == generation ? entry : null;
    }

    private static boolean isCurrent(String routeId, long generation) {
        CastRouteLifecycleRegistry.Snapshot snapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(routeId);
        return snapshot.ownsController && !snapshot.released && snapshot.generation == generation;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_ROUTE_VOLUME, value));
    }

    private static final class Entry {
        final long generation;
        final Transport transport;
        volatile int currentVolume;
        volatile boolean currentMuted;
        volatile boolean currentMutedKnown;
        volatile boolean togglePending;
        private boolean pendingExpectedMuted;
        private boolean active = true;

        Entry(long generation, Transport transport, int currentVolume) {
            this.generation = generation;
            this.transport = transport;
            this.currentVolume = currentVolume;
        }

        synchronized boolean setVolume(int requestedVolume, float volume) {
            if (!active || !transport.isConnected()) return false;
            try {
                transport.setVolume(volume);
                currentVolume = requestedVolume;
                return true;
            } catch (Exception failure) {
                Log.w("CastRouteVolumeRegistry", "absoluteVolumeFailed type="
                        + failure.getClass().getSimpleName());
                return false;
            }
        }

        synchronized boolean updateVolume(int delta, float increment) {
            if (!active || !transport.isConnected()) return false;
            try {
                transport.setVolumeByIncrement(increment);
                currentVolume = clamp(currentVolume + delta);
                return true;
            } catch (Exception failure) {
                Log.w("CastRouteVolumeRegistry", "relativeVolumeFailed type="
                        + failure.getClass().getSimpleName());
                return false;
            }
        }

        synchronized boolean setMuted(String routeId, long expectedGeneration, boolean muted) {
            if (!active || generation != expectedGeneration || ENTRIES.get(routeId) != this
                    || !transport.isConnected()) return false;
            try {
                transport.setMuted(muted);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        synchronized boolean toggleMuted(String routeId, long expectedGeneration) {
            if (!active || generation != expectedGeneration || ENTRIES.get(routeId) != this
                    || !transport.isConnected() || !currentMutedKnown || togglePending) return false;
            boolean desiredMuted = !currentMuted;
            pendingExpectedMuted = desiredMuted;
            togglePending = true;
            try {
                transport.setMuted(desiredMuted);
                return true;
            } catch (Exception ignored) {
                clearPendingToggle();
                return false;
            }
        }

        synchronized boolean updateFromReceiver(int volume, boolean muted) {
            if (!active) return false;
            boolean changed = currentVolume != volume || currentMuted != muted;
            currentVolume = volume;
            currentMuted = muted;
            currentMutedKnown = true;
            togglePending = false;
            pendingExpectedMuted = false;
            return changed;
        }

        synchronized void clearPendingToggle() {
            togglePending = false;
            pendingExpectedMuted = false;
        }

        synchronized void invalidateReceiverState() {
            currentMutedKnown = false;
            clearPendingToggle();
        }

        synchronized void deactivate() {
            active = false;
            invalidateReceiverState();
        }
    }
}
