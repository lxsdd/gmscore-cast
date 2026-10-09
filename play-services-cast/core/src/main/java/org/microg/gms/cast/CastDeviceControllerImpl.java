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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.ApplicationStatus;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastDeviceStatus;
import com.google.android.gms.cast.JoinOptions;
import com.google.android.gms.cast.LaunchOptions;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.cast.internal.CastRouteLifecycleRegistry;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.images.WebImage;
import com.google.android.gms.common.internal.BinderWrapper;
import com.google.android.gms.common.internal.GetServiceRequest;

import su.litvak.chromecast.api.v2.Application;
import su.litvak.chromecast.api.v2.ChromeCast;
import su.litvak.chromecast.api.v2.ChromeCastLaunchOptions;
import su.litvak.chromecast.api.v2.Namespace;
import su.litvak.chromecast.api.v2.ChromeCastConnectionEventListener;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEventListener;
import su.litvak.chromecast.api.v2.ChromeCastRawMessageListener;
import su.litvak.chromecast.api.v2.ChromeCastConnectionEvent;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEvent;
import su.litvak.chromecast.api.v2.ChromeCastRawMessage;
import su.litvak.chromecast.api.v2.AppEvent;

public class CastDeviceControllerImpl extends ICastDeviceController.Stub implements
    ChromeCastConnectionEventListener,
    ChromeCastSpontaneousEventListener,
    ChromeCastRawMessageListener,
    ICastDeviceControllerListener
{
    private static final String TAG = "GmsCastDeviceController";
    static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 250L;
    private static final ScheduledExecutorService RECONNECT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();

    private Context context;
    private String packageName;
    private CastDevice castDevice;
    boolean notificationEnabled;
    long castFlags;
    volatile ICastDeviceControllerListener listener;
    private IBinder listenerBinder;
    private IBinder.DeathRecipient listenerDeathRecipient;
    // A queued death from a previous registration must never affect its replacement,
    // including the case where the same Binder object is registered again.
    private long listenerRegistrationEpoch;

    ChromeCast chromecast;

    private final String routeId;
    private final long routeControllerGeneration;
    private final CastRouteVolumeRegistry.Transport routeVolumeTransport =
            new CastRouteVolumeRegistry.Transport() {
                @Override
                public void setVolume(float volume) throws Exception {
                    chromecast.setVolume(volume);
                }

                @Override
                public void setVolumeByIncrement(float increment) throws Exception {
                    // The library's similarly named method ramps to an absolute target.
                    // Android supplies a signed delta; apply it to the receiver's level.
                    float current = chromecast.getStatus().volume.level;
                    chromecast.setVolume(Math.max(0f, Math.min(1f, current + increment)));
                }

                @Override
                public void setMuted(boolean muted) throws Exception {
                    chromecast.setMuted(muted);
                }

                @Override
                public boolean isConnected() {
                    return chromecast != null && chromecast.isConnected();
                }
            };

    String sessionId = null;
    private volatile boolean disconnectRequested;
    private int reconnectAttempts;
    private boolean reconnectScheduled;

    private synchronized boolean replaceListener(ICastDeviceControllerListener newListener) {
        clearListenerLocked();
        if (newListener == null) return true;

        IBinder binder = newListener.asBinder();
        final long registrationEpoch = listenerRegistrationEpoch;
        IBinder.DeathRecipient recipient = () -> handleListenerDeath(binder, registrationEpoch);
        this.listener = newListener;
        this.listenerBinder = binder;
        this.listenerDeathRecipient = recipient;
        try {
            binder.linkToDeath(recipient, 0);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to link Cast client death: " + e.getMessage());
            handleListenerDeath(binder, registrationEpoch);
            return false;
        }
    }

    private synchronized void clearListener() {
        clearListenerLocked();
    }

    private void clearListenerLocked() {
        listenerRegistrationEpoch++;
        IBinder binder = this.listenerBinder;
        IBinder.DeathRecipient recipient = this.listenerDeathRecipient;
        this.listener = null;
        this.listenerBinder = null;
        this.listenerDeathRecipient = null;
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (RuntimeException ignored) {
                // The listener may already be dead/unlinked.
            }
        }
    }

    // Conditional cleanup must compare the registration, not merely its Binder.
    static boolean sameListenerRegistration(IBinder currentBinder,
            IBinder.DeathRecipient currentRecipient, long currentEpoch,
            IBinder expectedBinder, IBinder.DeathRecipient expectedRecipient, long expectedEpoch) {
        return currentBinder == expectedBinder
                && currentRecipient == expectedRecipient
                && currentEpoch == expectedEpoch;
    }

    private void handleListenerDeath(IBinder deadBinder, long deathEpoch) {
        synchronized (this) {
            if (deadBinder != listenerBinder || deathEpoch != listenerRegistrationEpoch) return;
            listenerRegistrationEpoch++;
            this.listener = null;
            this.listenerBinder = null;
            this.listenerDeathRecipient = null;
            disconnectRequested = true;
        }
        disconnectRequested = true;
        CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
        CastRouteVolumeRegistry.unregister(routeId, routeControllerGeneration, routeVolumeTransport);
        disconnectTransport();
    }

    private synchronized void disconnectTransport() {
        try {
            this.chromecast.disconnect();
        } catch (IOException e) {
            Log.e(TAG, "Error disconnecting chromecast: " + e.getMessage());
        }
    }

    boolean hasInitialListener() {
        return listener != null;
    }

    int connectBeforeInit() {
        disconnectRequested = false;
        reconnectAttempts = 0;
        return connectTransport();
    }

    private synchronized int connectTransport() {
        if (disconnectRequested) return CommonStatusCodes.NETWORK_ERROR;
        CastRouteLifecycleRegistry.markConnecting(routeControllerGeneration);
        try {
            if (!this.chromecast.isConnected()) {
                this.chromecast.connect();
            }
            boolean volumeRegistered = CastRouteVolumeRegistry.register(
                    routeId, routeControllerGeneration, routeVolumeTransport);
            CastRouteLifecycleRegistry.Snapshot volumeRoute =
                    CastRouteLifecycleRegistry.snapshotForRoute(routeId);
            synchronized (this) {
                reconnectAttempts = 0;
                reconnectScheduled = false;
            }
            return CommonStatusCodes.SUCCESS;
        } catch (Exception e) {
            CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
            Log.w(TAG, "Error connecting to chromecast: " + e.getMessage());
            return CommonStatusCodes.NETWORK_ERROR;
        }
    }

    public CastDeviceControllerImpl(Context context, String packageName, Bundle extras) {
        this.context = context;
        this.packageName = packageName;

        extras.setClassLoader(BinderWrapper.class.getClassLoader());
        this.castDevice = CastDevice.getFromBundle(extras);
        this.routeId = this.castDevice == null ? null : this.castDevice.getDeviceId();
        CastRouteLifecycleRegistry.Snapshot routeSnapshot =
                CastRouteLifecycleRegistry.snapshotForRoute(this.routeId);
        this.routeControllerGeneration = routeSnapshot.ownsController && !routeSnapshot.released
                ? routeSnapshot.generation : 0;
        this.notificationEnabled = extras.getBoolean("com.google.android.gms.cast.EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED");
        this.castFlags = extras.getLong("com.google.android.gms.cast.EXTRA_CAST_FLAGS");
        // Capture the descriptor endpoint for this controller. Rediscovery can replace the
        // descriptor for a later controller, but must never move this live transport.
        this.chromecast = CastEndpointFactory.create(
                this.castDevice.getAddress(), this.castDevice.getServicePort());
        this.chromecast.registerListener(this);
        this.chromecast.registerRawMessageListener(this);
        this.chromecast.registerConnectionListener(this);

        // Link legacy listeners only after the transport object exists. A listener that is already
        // dead can synchronously fail linkToDeath(), which tears the controller down.
        BinderWrapper listenerWrapper = (BinderWrapper)extras.get("listener");
        if (listenerWrapper != null) {
            replaceListener(ICastDeviceControllerListener.Stub.asInterface(listenerWrapper.binder));
        }
    }

    @Override
    public void connectionEventReceived(ChromeCastConnectionEvent event) {
        if (!event.isConnected()) {
            CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
            CastRouteVolumeRegistry.unregister(
                    routeId, routeControllerGeneration, routeVolumeTransport);
            if (disconnectRequested) {
                this.onDisconnected(CommonStatusCodes.SUCCESS);
            } else {
                this.onConnectionSuspended(
                        com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST);
                scheduleReconnect();
            }
        }
    }

    protected ApplicationMetadata createMetadataFromApplication(Application app) {
        if (app == null) {
            return null;
        }
        ApplicationMetadata metadata = new ApplicationMetadata();
        metadata.applicationId = app.id;
        metadata.name = app.name;
        metadata.images = new ArrayList<WebImage>();
        metadata.namespaces = new ArrayList<String>();
        for(Namespace namespace : app.namespaces) {
            metadata.namespaces.add(namespace.name);
        }
        metadata.senderAppIdentifier = this.context.getPackageName();
        return metadata;
    }

    @Override
    public void spontaneousEventReceived(ChromeCastSpontaneousEvent event) {
        switch (event.getType()) {
            case MEDIA_STATUS:
                break;
            case STATUS:
                publishReceiverStatus((su.litvak.chromecast.api.v2.Status) event.getData());
                break;
            case APPEVENT:
                break;
            case CLOSE:
                this.onApplicationDisconnected(CommonStatusCodes.SUCCESS);
                break;
            default:
                break;
        }
    }

    private void publishReceiverStatus(su.litvak.chromecast.api.v2.Status status) {
        if (status == null) return;
        CastRouteVolumeRegistry.updateFromReceiver(
                routeId, routeControllerGeneration, status.volume.level, status.volume.muted);
        Application app = status.getRunningApp();
        ApplicationMetadata metadata = this.createMetadataFromApplication(app);
        if (app != null) {
            this.onApplicationStatusChanged(new ApplicationStatus(app.statusText));
        }
        int activeInputState = status.activeInput ? 1 : 0;
        int standbyState = status.standBy ? 1 : 0;
        this.onDeviceStatusChanged(new CastDeviceStatus(
                status.volume.level, status.volume.muted, activeInputState, metadata, standbyState));
    }

    @Override
    public void rawMessageReceived(ChromeCastRawMessage message, Long requestId) {
        switch (message.getPayloadType()) {
            case STRING:
                String response = message.getPayloadUtf8();
                // Every inbound text message is an application-level message (e.g. MEDIA_STATUS)
                // and must be delivered via onTextMessageReceived. Do NOT report it as a send
                // success here: onSendMessageSuccess is keyed by the *outgoing* send's requestId
                // (signalled in sendMessage), not the response's requestId — conflating them
                // completes a non-existent client task and throws a RemoteException.
                this.onTextMessageReceived(message.getNamespace(), response);
                break;
            case BINARY:
                byte[] payload = message.getPayloadBinary();
                this.onBinaryMessageReceived(message.getNamespace(), payload);
                break;
        }
    }

    @Override
    public void connect() {
        // CXLESS readiness is distinct from the GMS service bind.
        disconnectRequested = false;
        synchronized (this) {
            reconnectAttempts = 0;
            reconnectScheduled = false;
        }
        this.onConnectedWithResult(connectTransport());
    }

    private void scheduleReconnect() {
        final int attempt;
        synchronized (this) {
            if (disconnectRequested || reconnectScheduled || listener == null) return;
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "Giving up Cast reconnect after " + reconnectAttempts + " attempts");
                disconnectRequested = true;
                onDisconnected(CommonStatusCodes.NETWORK_ERROR);
                return;
            }
            reconnectScheduled = true;
            attempt = ++reconnectAttempts;
        }
        RECONNECT_EXECUTOR.schedule(() -> {
            synchronized (CastDeviceControllerImpl.this) {
                reconnectScheduled = false;
                if (disconnectRequested || listener == null) return;
            }
            int status = connectTransport();
            if (status == CommonStatusCodes.SUCCESS) {
                onConnectedWithResult(status);
            } else {
                scheduleReconnect();
            }
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setListener(ICastDeviceControllerListener listener) {
        if (!replaceListener(listener)) {
            disconnectRequested = true;
            disconnectTransport();
        }
    }

    @Override
    public void unregisterListener() {
        clearListener();
    }

    @Override
    public void setMute(boolean mute) {
        boolean applied = CastRouteVolumeRegistry.setMuted(
                routeId, routeControllerGeneration, mute);
    }

    @Override
    public boolean isMute() {
        return CastRouteVolumeRegistry.mutedForRoute(routeId);
    }

    @Override
    public double getVolume() {
        return CastRouteVolumeRegistry.volumeForRoute(routeId)
                / (double) CastRouteVolumeRegistry.MAX_ROUTE_VOLUME;
    }

    @Override
    public void disconnect() {
        final IBinder disconnectBinder;
        final IBinder.DeathRecipient disconnectRecipient;
        final long disconnectEpoch;
        synchronized (this) {
            disconnectRequested = true;
            disconnectBinder = listenerBinder;
            disconnectRecipient = listenerDeathRecipient;
            disconnectEpoch = listenerRegistrationEpoch;
        }
        CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
        CastRouteVolumeRegistry.unregister(
                routeId, routeControllerGeneration, routeVolumeTransport);
        try {
            // Keep the live listener until the transport has synchronously emitted its
            // onDisconnected event. This preserves the legitimate disconnect callback.
            disconnectTransport();
        } finally {
            synchronized (this) {
                if (sameListenerRegistration(listenerBinder, listenerDeathRecipient,
                        listenerRegistrationEpoch, disconnectBinder, disconnectRecipient,
                        disconnectEpoch)) {
                    clearListenerLocked();
                }
            }
        }
    }

    @Override
    public void leaveApplication() {
        // Leave detaches this sender but intentionally does not stop the receiver application.
        this.sessionId = null;
        onLeaveApplicationResult(CommonStatusCodes.SUCCESS);
    }

    @Override
    public void requestStatus() {
        try {
            publishReceiverStatus(this.chromecast.getStatus());
        } catch (IOException e) {
            Log.w(TAG, "requestStatus failed: " + e.getMessage());
        }
    }

    @Override
    public void setVolume(double level, double expectedLevel, boolean expectedMute) {
        if (!Double.isFinite(level)) return;
        float clamped = (float) Math.max(0d, Math.min(1d, level));
        try {
            this.chromecast.setVolume(clamped);
        } catch (IOException e) {
            Log.w(TAG, "setVolume failed: " + e.getMessage());
        }
    }

    @Override
    public void setMuteExpected(boolean mute, double expectedLevel, boolean expectedMute) {
        try {
            this.chromecast.setMuted(mute);
        } catch (IOException e) {
            Log.w(TAG, "setMute failed: " + e.getMessage());
        }
    }

    @Override
    public void sendBinaryMessage(String namespace, byte[] message, long requestId) {
        // The retained raw-request transport exposes text sends but no verified binary-send API.
        // Surface an explicit failure rather than silently acknowledging data that was never sent.
        Log.w(TAG, "Binary Cast requests are not supported by this transport");
        onSendMessageFailure(namespace, requestId, CommonStatusCodes.NETWORK_ERROR);
    }

    @Override
    public void sendMessage(String namespace, String message, long requestId) {
        try {
            this.chromecast.sendRawRequest(namespace, message, requestId);
            // Signal transport-level send success keyed by the outgoing send's requestId so the
            // client's sendMessage Task completes; the receiver's reply arrives separately as an
            // inbound message via onTextMessageReceived.
            this.onSendMessageSuccess("", requestId);
        } catch (IOException e) {
            Log.w(TAG, "Error sending cast message: " + e.getMessage());
            this.onSendMessageFailure("", requestId, CommonStatusCodes.NETWORK_ERROR);
            return;
        }
    }

    @Override
    public void stopApplication(String sessionId) {
        try {
            this.chromecast.stopSession(sessionId);
            this.sessionId = null;
            onStopApplicationResult(CommonStatusCodes.SUCCESS);
        } catch (IOException e) {
            Log.w(TAG, "Error stopping cast session: " + e.getMessage());
            onStopApplicationResult(CommonStatusCodes.NETWORK_ERROR);
        }
    }

    @Override
    public void registerNamespace(String namespace) {
    }

    @Override
    public void unregisterNamespace(String namespace) {
    }

    @Override
    public void launchApplication(String applicationId, LaunchOptions launchOptions) {
        String language = launchOptions == null ? null : launchOptions.getLanguage();
        boolean androidReceiverCompatible = launchOptions != null
                && launchOptions.getAndroidReceiverCompatible();
        Application app = null;
        try {
            app = ChromeCastLaunchOptions.launchApp(this.chromecast, applicationId, language,
                    androidReceiverCompatible);
        } catch (IOException e) {
            Log.w(TAG, "Error launching cast application: " + e.getMessage());
            String receiverResult = e.getMessage() != null
                    && e.getMessage().contains("NOT_ALLOWED") ? "NOT_ALLOWED" : "OTHER_FAILURE";
            this.onApplicationConnectionFailure(CommonStatusCodes.NETWORK_ERROR);
            return;
        }
        this.sessionId = app.sessionId;

        ApplicationMetadata metadata = this.createMetadataFromApplication(app);
        this.onApplicationConnectionSuccess(metadata, app.statusText, app.sessionId, true);
    }

    @Override
    public void joinApplication(String applicationId, String sessionId, JoinOptions joinOptions) {
        try {
            su.litvak.chromecast.api.v2.Status status = this.chromecast.getStatus();
            Application app = status == null ? null : status.getRunningApp();
            boolean appMatches = app != null
                    && (applicationId == null || applicationId.equals(app.id));
            boolean sessionMatches = app != null
                    && (sessionId == null || sessionId.equals(app.sessionId));
            if (!appMatches || !sessionMatches) {
                onApplicationConnectionFailure(CommonStatusCodes.NETWORK_ERROR);
                return;
            }
            this.sessionId = app.sessionId;
            onApplicationConnectionSuccess(
                    createMetadataFromApplication(app), app.statusText, app.sessionId, false);
        } catch (IOException e) {
            Log.w(TAG, "Error joining cast application: " + e.getMessage());
            onApplicationConnectionFailure(CommonStatusCodes.NETWORK_ERROR);
        }
    }

    public void onLeaveApplicationResult(int statusCode) {
        ICastDeviceControllerListener l = this.listener;
        if (l != null) {
            try {
                l.onLeaveApplicationResult(statusCode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onLeaveApplicationResult: " + ex.getMessage());
            }
        }
    }

    public void onStopApplicationResult(int statusCode) {
        ICastDeviceControllerListener l = this.listener;
        if (l != null) {
            try {
                l.onStopApplicationResult(statusCode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onStopApplicationResult: " + ex.getMessage());
            }
        }
    }

    public void onConnectionSuspended(int reason) {
        ICastDeviceControllerListener l = this.listener;
        if (l != null) {
            try {
                l.onConnectionSuspended(reason);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onConnectionSuspended: " + ex.getMessage());
            }
        }
    }

    public void onDisconnected(int reason) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onDisconnected(reason);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onDisconnected: " + ex.getMessage());
            }
        }
    }

    public void onApplicationConnectionSuccess(ApplicationMetadata applicationMetadata, String applicationStatus, String sessionId, boolean wasLaunched) {
        CastRouteLifecycleRegistry.markConnected(routeControllerGeneration);
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onApplicationConnectionSuccess(applicationMetadata, applicationStatus, sessionId, wasLaunched);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onApplicationConnectionSuccess: " + ex.getMessage());
            }
        }
    }

    public void onApplicationConnectionFailure(int statusCode) {
        CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onApplicationConnectionFailure(statusCode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onApplicationConnectionFailure: " + ex.getMessage());
            }
        }
    }

    public void onTextMessageReceived(String namespace, String message) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onTextMessageReceived(namespace, message);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onTextMessageReceived: " + ex.getMessage());
            }
        } else {
        }
    }

    public void onBinaryMessageReceived(String namespace, byte[] data) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onBinaryMessageReceived(namespace, data);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onBinaryMessageReceived: " + ex.getMessage());
            }
        } else {
        }
    }

    public void onApplicationDisconnected(int paramInt) {
        CastRouteLifecycleRegistry.markDisconnected(routeControllerGeneration);
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onApplicationDisconnected(paramInt);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onApplicationDisconnected: " + ex.getMessage());
            }
        }
    }

    public void onSendMessageFailure(String response, long requestId, int statusCode) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onSendMessageFailure(response, requestId, statusCode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onSendMessageFailure: " + ex.getMessage());
            }
        }
    }

    public void onSendMessageSuccess(String response, long requestId) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onSendMessageSuccess(response, requestId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onSendMessageSuccess: " + ex.getMessage());
            }
        }
    }

    public void onApplicationStatusChanged(ApplicationStatus applicationStatus) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onApplicationStatusChanged(applicationStatus);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onApplicationStatusChanged: " + ex.getMessage());
            }
        }
    }

    public void onDeviceStatusChanged(CastDeviceStatus deviceStatus) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onDeviceStatusChanged(deviceStatus);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onDeviceStatusChanged: " + ex.getMessage());
            }
        }
    }

    public void onConnectedWithResult(int statusCode) {
        ICastDeviceControllerListener callback = this.listener;
        if (callback != null) {
            try {
                callback.onConnectedWithResult(statusCode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling onConnectedWithResult: " + ex.getMessage());
            }
        }
    }
}
