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

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.common.api.GoogleApiClient;

import org.microg.gms.common.GmsClient;
import org.microg.gms.common.GmsService;
import com.google.android.gms.common.api.internal.ConnectionCallbacks;
import org.microg.gms.common.api.GoogleApiClientImpl;
import com.google.android.gms.common.api.internal.OnConnectionFailedListener;

import java.io.IOException;

public class CastClientImpl extends GmsClient<ICastDeviceController> {
    public CastClientImpl(Context context, Cast.CastOptions options, ConnectionCallbacks callbacks, OnConnectionFailedListener connectionFailedListener) {
        super(context, callbacks, connectionFailedListener, GmsService.CAST.ACTION);
        serviceId = GmsService.CAST.SERVICE_ID;
        if (options != null && options.getCastDevice() != null) {
            options.getCastDevice().putInBundle(extras);
        }
    }

    @Override
    protected ICastDeviceController interfaceFromBinder(IBinder binder) {
        return ICastDeviceController.Stub.asInterface(binder);
    }

    static CastClientImpl get(GoogleApiClient client) {
        if (!(client instanceof GoogleApiClientImpl)) return null;
        Object connection = ((GoogleApiClientImpl) client).getApiConnection(Cast.API);
        return connection instanceof CastClientImpl ? (CastClientImpl) connection : null;
    }

    void setMute(boolean mute) throws IOException {
        try {
            getServiceInterface().setMute(mute);
        } catch (RemoteException | IllegalStateException failure) {
            throw new IOException("Cast controller is not available", failure);
        }
    }

    boolean isMute() {
        try {
            return getServiceInterface().isMute();
        } catch (RemoteException | IllegalStateException failure) {
            return false;
        }
    }

    double getVolume() {
        try {
            return getServiceInterface().getVolume();
        } catch (RemoteException | IllegalStateException failure) {
            return 0d;
        }
    }
}
