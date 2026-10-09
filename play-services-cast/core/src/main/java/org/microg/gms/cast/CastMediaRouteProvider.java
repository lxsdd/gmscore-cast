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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteDiscoveryRequest;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.common.images.WebImage;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.Thread;
import java.lang.Runnable;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

public class CastMediaRouteProvider extends MediaRouteProvider {
    private static final String TAG = CastMediaRouteProvider.class.getSimpleName();

    private final CastRouteDiscoveryState<CastDevice> routeState = new CastRouteDiscoveryState<>();
    private final CastRouteLifecycleRegistry.Listener lifecycleListener =
            (routeId, generation, connectionState) -> {
        if (routeState.getDevice(routeId) != null) publishRoutesInMainThread();
    };
    private final CastRouteVolumeRegistry.Listener volumeListener = routeId -> {
        if (routeState.getDevice(routeId) != null) publishRoutesInMainThread();
    };

    private NsdManager mNsdManager;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CastDnsSdResolveQueue<NsdServiceInfo> resolveQueue =
            new CastDnsSdResolveQueue<>();
    private boolean scanRequested;



    private enum State {
        NOT_DISCOVERING,
        DISCOVERY_REQUESTED,
        DISCOVERING,
        DISCOVERY_STOP_REQUESTED,
    }
    private State state = State.NOT_DISCOVERING;

    private static final String[] REMOTE_PLAYBACK_TYPES = {
            "image/jpeg",
            "image/pjpeg",
            "image/jpg",
            "image/webp",
            "image/png",
            "image/gif",
            "image/bmp",
            "image/vnd.microsoft.icon",
            "image/x-icon",
            "image/x-xbitmap",
            "audio/wav",
            "audio/x-wav",
            "audio/mp3",
            "audio/x-mp3",
            "audio/x-m4a",
            "audio/mpeg",
            "audio/webm",
            "audio/ogg",
            "audio/x-matroska",
            "video/mp4",
            "video/x-m4v",
            "video/mp2t",
            "video/webm",
            "video/ogg",
            "video/x-matroska",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
    };

    private static final ArrayList<IntentFilter> BASE_CONTROL_FILTERS = new ArrayList<IntentFilter>();
    static {
        IntentFilter filter;

        filter = new IntentFilter();
        filter.addCategory(CastMediaControlIntent.CATEGORY_CAST);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_PAUSE);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_RESUME);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_STOP);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_SEEK);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_GET_STATUS);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_START_SESSION);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_GET_SESSION_STATUS);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_END_SESSION);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(CastMediaControlIntent.CATEGORY_CAST_REMOTE_PLAYBACK);
        filter.addAction(CastMediaControlIntent.ACTION_SYNC_STATUS);
        BASE_CONTROL_FILTERS.add(filter);

        filter = new IntentFilter();
        filter.addCategory(CastMediaControlIntent.CATEGORY_CAST_REMOTE_PLAYBACK);
        filter.addAction(CastMediaControlIntent.ACTION_SYNC_STATUS);
        BASE_CONTROL_FILTERS.add(filter);
    }

    @SuppressLint("NewApi")
    public CastMediaRouteProvider(Context context) {
        super(context);
        CastRouteLifecycleRegistry.addListener(lifecycleListener);
        CastRouteVolumeRegistry.addListener(volumeListener);

        if (android.os.Build.VERSION.SDK_INT < 16) {
            Log.i(TAG, "Cast discovery disabled. Android SDK version 16 or higher required.");
            return;
        }

        mNsdManager = (NsdManager)context.getApplicationContext().getSystemService(Context.NSD_SERVICE);

    }

    private void runOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
        }
    }

    private NsdManager.DiscoveryListener newDiscoveryListener(final long scanGeneration) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                runOnMain(() -> {
                    if (mDiscoveryListener == this
                            && resolveQueue.isDiscoveryCurrent(scanGeneration)
                            && state == State.DISCOVERY_REQUESTED) {
                        state = State.DISCOVERING;
                    }
                });
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                runOnMain(() -> {
                    if (mDiscoveryListener != this
                            || !resolveQueue.isDiscoveryCurrent(scanGeneration)) return;
                    resolveQueue.found(service.getServiceName(), service);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                runOnMain(() -> {
                    if (mDiscoveryListener != this
                            || !resolveQueue.isDiscoveryCurrent(scanGeneration)) return;
                    resolveQueue.lost(service.getServiceName());
                    onChromeCastLost(service.getServiceName());
                });
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                runOnMain(() -> {
                    if (mDiscoveryListener != this) return;
                    resolveQueue.endDiscovery();
                    state = State.NOT_DISCOVERING;
                    mDiscoveryListener = null;
                    startDiscoveryIfRequested();
                });
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                runOnMain(() -> {
                    if (mDiscoveryListener != this) return;
                    Log.w(TAG, "Cast DNS-SD start failed: " + errorCode);
                    resolveQueue.endDiscovery();
                    state = State.NOT_DISCOVERING;
                    mDiscoveryListener = null;
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                runOnMain(() -> {
                    if (mDiscoveryListener != this) return;
                    Log.w(TAG, "Cast DNS-SD stop failed: " + errorCode);
                    // The framework still owns this listener. Do not start a
                    // new discovery until the old listener has stopped.
                    state = State.DISCOVERY_STOP_REQUESTED;
                });
            }
        };
    }

    private void startDiscoveryIfRequested() {
        if (!scanRequested || state != State.NOT_DISCOVERING || mNsdManager == null) return;
        long scanGeneration = resolveQueue.beginDiscovery();
        NsdManager.DiscoveryListener listener = newDiscoveryListener(scanGeneration);
        mDiscoveryListener = listener;
        state = State.DISCOVERY_REQUESTED;
        try {
            mNsdManager.discoverServices("_googlecast._tcp.",
                    NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cast DNS-SD discovery request failed", e);
            if (mDiscoveryListener == listener) {
                resolveQueue.endDiscovery();
                mDiscoveryListener = null;
                state = State.NOT_DISCOVERING;
            }
        }
    }

    private void resolveNext() {
        if (mNsdManager == null) return;
        CastDnsSdResolveQueue.Request<NsdServiceInfo> request = resolveQueue.takeNext();
        if (request == null) return;

        NsdManager.ResolveListener callback = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                runOnMain(() -> {
                    boolean current = resolveQueue.complete(request);
                    if (current && errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // Another Android NSD client may still hold the
                        // resolver. Retry at most twice, if still current.
                        mainHandler.postDelayed(() -> {
                            if (resolveQueue.retryAlreadyActive(request)) resolveNext();
                        }, 500);
                    } else if (current) {
                        Log.w(TAG, "Cast DNS-SD resolve failed: " + errorCode);
                    }
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo service) {
                runOnMain(() -> {
                    if (resolveQueue.complete(request)) {
                        handleResolvedService(service);
                    }
                    resolveNext();
                });
            }
        };
        try {
            mNsdManager.resolveService(request.service, callback);
        } catch (RuntimeException e) {
            resolveQueue.complete(request);
            Log.w(TAG, "Cast DNS-SD resolve request failed", e);
            resolveNext();
        }
    }

    private void handleResolvedService(NsdServiceInfo serviceInfo) {
        String name = serviceInfo.getServiceName();
        InetAddress host = serviceInfo.getHost();
        int port = serviceInfo.getPort();
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        if (attributes == null || host == null || port <= 0 || port > 65535) {
            Log.w(TAG, "Ignoring Cast DNS-SD result with missing endpoint or attributes");
            return;
        }
        try {
            String id = new String(attributes.get("id"), "UTF-8");
            String deviceVersion = new String(attributes.get("ve"), "UTF-8");
            String friendlyName = new String(attributes.get("fn"), "UTF-8");
            String modelName = new String(attributes.get("md"), "UTF-8");
            String iconPath = new String(attributes.get("ic"), "UTF-8");
            int status = Integer.parseInt(new String(attributes.get("st"), "UTF-8"));
            int capabilities = CastRouteCapabilities.fromDnsSdAttributes(attributes);

            onChromeCastDiscovered(id, name, host, port, deviceVersion,
                    friendlyName, modelName, iconPath, status, capabilities);
        } catch (UnsupportedEncodingException | NullPointerException | NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed Cast DNS-SD TXT record", e);
        }
    }

    private void onChromeCastDiscovered(
            String id, String name, InetAddress host, int port, String
            deviceVersion, String friendlyName, String modelName, String
            iconPath, int status, int capabilities) {
        CastDevice known = routeState.getDevice(id);
        boolean refresh = known == null
                || !Objects.equals(known.getAddress(), host.getHostAddress())
                || known.getServicePort() != port
                || known.getCapabilities() != capabilities
                || !Objects.equals(known.getFriendlyName(), friendlyName)
                || !Objects.equals(known.getModelName(), modelName);
        CastDevice castDevice = refresh
                ? new CastDevice(id, name, host, port, deviceVersion,
                        friendlyName, modelName, iconPath, status, capabilities) : known;
        if (routeState.putDevice(id, name, castDevice, refresh)) {
            publishRoutesInMainThread();
        }
    }

    private void onChromeCastLost(String name) {
        routeState.forgetService(name);

        publishRoutesInMainThread();
    }

    @SuppressLint("NewApi")
    @Override
    public void onDiscoveryRequestChanged(MediaRouteDiscoveryRequest request) {
        if (android.os.Build.VERSION.SDK_INT < 16) return;
        runOnMain(() -> handleDiscoveryRequest(request));
    }

    private void handleDiscoveryRequest(MediaRouteDiscoveryRequest request) {
        scanRequested = request != null && request.isValid() && request.isActiveScan();
        List<String> categories = new ArrayList<>();
        if (scanRequested && request.getSelector() != null) {
            for (String category : request.getSelector().getControlCategories()) {
                if (CastMediaControlIntent.isCategoryForCast(category)) {
                    categories.add(category);
                }
            }
        }
        if (routeState.replaceCategories(categories)) {
            publishRoutesInMainThread();
        }
        if (scanRequested) {
            startDiscoveryIfRequested();
        } else {
            resolveQueue.endDiscovery();
            if ((state == State.DISCOVERING || state == State.DISCOVERY_REQUESTED)
                    && mNsdManager != null && mDiscoveryListener != null) {
                state = State.DISCOVERY_STOP_REQUESTED;
                try {
                    mNsdManager.stopServiceDiscovery(mDiscoveryListener);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cast DNS-SD stop request failed", e);
                }
            }
        }
    }

    @Override
    public RouteController onCreateRouteController(String routeId) {
        CastDevice castDevice = routeState.getDevice(routeId);
        if (castDevice == null) return null;
        long generation = CastRouteLifecycleRegistry.controllerCreated(routeId);
        publishRoutesInMainThread();
        return new CastMediaRouteController(this, routeId, generation,
                CastRouteCapabilities.isAudioOnly(castDevice.getCapabilities()));
    }

    void onRouteControllerSelected(String routeId, Object token) {
        if (routeState.selectRoute(routeId, token)) publishRoutesInMainThread();
    }

    void onRouteControllerDeselected(String routeId, Object token) {
        if (routeState.releaseRoute(routeId, token)) publishRoutesInMainThread();
    }

    private void publishRoutesInMainThread() {
        Handler mainHandler = new Handler(this.getContext().getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                publishRoutes();
            }
        });
    }

    /** Advertise only media output the receiver claims; unknown keeps legacy compatibility. */
    private static IntentFilter createRemotePlaybackFilter(int capabilities) {
        IntentFilter filter = new IntentFilter();
        filter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        filter.addAction(MediaControlIntent.ACTION_PLAY);
        filter.addDataScheme("http");
        filter.addDataScheme("https");
        for (String mimeType : REMOTE_PLAYBACK_TYPES) {
            if (!CastRouteCapabilities.advertisesMimeType(capabilities, mimeType)) continue;
            try {
                filter.addDataType(mimeType);
            } catch (IntentFilter.MalformedMimeTypeException e) {
                Log.w(TAG, "Ignoring malformed Cast remote playback MIME type", e);
            }
        }
        return filter;
    }

    private void publishRoutes() {
        CastRouteDiscoveryState.Snapshot<CastDevice> snapshot = routeState.snapshot();
        MediaRouteProviderDescriptor.Builder builder = new MediaRouteProviderDescriptor.Builder();
        for (CastDevice castDevice : snapshot.devices) {
            ArrayList<IntentFilter> controlFilters = new ArrayList<IntentFilter>(BASE_CONTROL_FILTERS);
            controlFilters.add(createRemotePlaybackFilter(castDevice.getCapabilities()));
            if (CastRouteCapabilities.isAudioOnly(castDevice.getCapabilities())) {
                IntentFilter muteFilter = new IntentFilter();
                muteFilter.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
                muteFilter.addAction(CastMediaRouteController.ACTION_SET_MUTED);
                muteFilter.addAction(CastMediaRouteController.ACTION_TOGGLE_MUTED);
                controlFilters.add(muteFilter);
            }
            // Include any app-specific control filters that have been requested.
            // TODO: Do we need to check with the device?
            for (String category : snapshot.categories) {
                IntentFilter filter = new IntentFilter();
                filter.addCategory(category);
                controlFilters.add(filter);
            }

            Bundle extras = new Bundle();
            castDevice.putInBundle(extras);
            MediaRouteDescriptor route = new MediaRouteDescriptor.Builder(
                castDevice.getDeviceId(),
                castDevice.getFriendlyName())
                .setDescription(castDevice.getModelName())
                .addControlFilters(controlFilters)
                .setDeviceType(CastRouteCapabilities.mediaRouterDeviceType(castDevice.getCapabilities()))
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setVolumeHandling(CastRouteCapabilities.has(
                        castDevice.getCapabilities(), CastDevice.CAPABILITY_AUDIO_OUT)
                        ? MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
                        : MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
                .setVolumeMax(CastRouteVolumeRegistry.MAX_ROUTE_VOLUME)
                .setVolume(CastRouteVolumeRegistry.volumeForRoute(castDevice.getDeviceId()))
                .setEnabled(true)
                .setExtras(extras)
                .setConnectionState(CastRouteLifecycleRegistry.snapshotForRoute(
                        castDevice.getDeviceId()).connectionState)
                .build();
            builder.addRoute(route);
        }
        this.setDescriptor(builder.build());
    }
}
