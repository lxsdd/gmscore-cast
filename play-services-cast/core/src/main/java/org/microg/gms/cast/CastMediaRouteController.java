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
package org.microg.gms.cast;

import android.content.Intent;
import android.os.Bundle;

import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaRouter-side controller. The actual CastV2 socket is owned by
 * CastDeviceControllerImpl, not by this class; selecting a route only
 * registers a generation and starts the expected connection lifecycle.
 */
public class CastMediaRouteController extends MediaRouteProvider.RouteController {
    static final String ACTION_SET_MUTED = "org.microg.gms.cast.action.SET_MUTED";
    static final String ACTION_TOGGLE_MUTED = "org.microg.gms.cast.action.TOGGLE_MUTED";
    static final String EXTRA_MUTED = "org.microg.gms.cast.extra.MUTED";

    private static final ExecutorService VOLUME_COMMANDS =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CastRouteVolume");
                thread.setDaemon(true);
                return thread;
            });

    private final CastMediaRouteProvider provider;
    private final String routeId;
    private final long controllerGeneration;
    private final boolean audioOnly;
    private volatile boolean released;

    public CastMediaRouteController(CastMediaRouteProvider provider, String routeId,
                                    long controllerGeneration, boolean audioOnly) {
        this.provider = provider;
        this.routeId = routeId;
        this.controllerGeneration = controllerGeneration;
        this.audioOnly = audioOnly;
    }

    @Override
    public boolean onControlRequest(Intent intent, MediaRouter.ControlRequestCallback callback) {
        if (intent == null || released || !audioOnly) return false;
        final String action = intent.getAction();
        if (!ACTION_SET_MUTED.equals(action) && !ACTION_TOGGLE_MUTED.equals(action)) {
            return false;
        }
        Object requestedMuted;
        try {
            Bundle extras = intent.getExtras();
            if (ACTION_TOGGLE_MUTED.equals(action) && extras != null && !extras.isEmpty()) {
                return false;
            }
            requestedMuted = extras == null ? null : extras.get(EXTRA_MUTED);
        } catch (RuntimeException invalidExtras) {
            return false;
        }
        boolean applied = handleMuteControlRequest(action, requestedMuted);
        if (applied && callback != null) callback.onResult(new Bundle());
        return applied;
    }

    boolean handleMuteControlRequest(String action, Object mutedValue) {
        if (released || !audioOnly) return false;
        if (ACTION_TOGGLE_MUTED.equals(action)) {
            return mutedValue == null
                    && CastRouteVolumeRegistry.toggleMuted(routeId, controllerGeneration);
        }
        return ACTION_SET_MUTED.equals(action) && mutedValue instanceof Boolean
                && CastRouteVolumeRegistry.setMuted(
                        routeId, controllerGeneration, (Boolean) mutedValue);
    }

    @Override
    public void onSelect() {
        if (released) return;
        provider.onRouteControllerSelected(routeId, this);
        CastRouteLifecycleRegistry.markConnecting(controllerGeneration);
    }

    @Override
    public void onUnselect() {
        if (released) return;
        CastRouteLifecycleRegistry.markDisconnected(controllerGeneration);
        provider.onRouteControllerDeselected(routeId, this);
    }

    @Override
    public void onUnselect(int reason) {
        onUnselect();
    }

    @Override
    public void onRelease() {
        if (released) return;
        released = true;
        CastRouteLifecycleRegistry.controllerReleased(controllerGeneration);
        provider.onRouteControllerDeselected(routeId, this);
        CastRouteLifecycleRegistry.forget(controllerGeneration);
    }

    @Override
    public void onSetVolume(int volume) {
        if (released) return;
        VOLUME_COMMANDS.execute(() -> {
            if (!released) CastRouteVolumeRegistry.setVolume(
                    routeId, controllerGeneration, volume);
        });
    }

    @Override
    public void onUpdateVolume(int delta) {
        if (released) return;
        VOLUME_COMMANDS.execute(() -> {
            if (!released) CastRouteVolumeRegistry.updateVolume(
                    routeId, controllerGeneration, delta);
        });
    }
}
