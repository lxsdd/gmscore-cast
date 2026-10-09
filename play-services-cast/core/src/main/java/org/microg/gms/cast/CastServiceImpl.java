/*
 * SPDX-FileCopyrightText: 2026 ReVanced
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.RequestItem;
import com.google.android.gms.cast.internal.IBundleCallback;
import com.google.android.gms.cast.internal.ICastService;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.api.internal.IStatusCallback;

import java.util.List;

/**
 * Device-independent CAST_API surface. These calls must never eagerly connect
 * to a receiver, launch a Cast app, or report playback success.
 */
public final class CastServiceImpl extends ICastService.Stub {
    private static final String TAG = "GmsCastService";

    @Override
    public void broadcastPrecacheMessageLegacy(
            IStatusCallback callback, String[] namespaces, String precacheData) {
        replyStatus(callback, Status.SUCCESS);
    }

    @Override
    public void broadcastPrecacheMessage(IStatusCallback callback, String[] namespaces,
                                         String precacheData, List<RequestItem> items) {
        replyStatus(callback, Status.SUCCESS);
    }

    @Override
    public void getCxLessStatus(IStatusCallback callback) {
        replyStatus(callback, Status.SUCCESS);
    }

    @Override
    public void getFeatureFlags(IBundleCallback callback, String[] flags) {
        replyBundle(callback, new Bundle());
    }

    @Override
    public void getCastStatusCodeDictionary(IBundleCallback callback, String[] dictionaries) {
        replyBundle(callback, new Bundle());
    }

    @Override
    public void getIntegerMaps(IBundleCallback callback, String[] keys) {
        replyBundle(callback, new Bundle());
    }

    private static void replyStatus(IStatusCallback callback, Status status) {
        if (callback == null) return;
        try {
            callback.onResult(status);
        } catch (RemoteException e) {
            Log.w(TAG, "Cast status callback binder died", e);
        }
    }

    private static void replyBundle(IBundleCallback callback, Bundle bundle) {
        if (callback == null) return;
        try {
            callback.onBundle(bundle);
        } catch (RemoteException e) {
            Log.w(TAG, "Cast query callback binder died", e);
        }
    }
}
