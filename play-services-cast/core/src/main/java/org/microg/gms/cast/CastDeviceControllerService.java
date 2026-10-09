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

import android.os.IBinder;
import android.os.RemoteException;
import android.os.Parcel;
import android.util.ArrayMap;
import android.util.Log;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.common.Feature;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.BinderWrapper;
import com.google.android.gms.common.internal.IGmsCallbacks;

import org.microg.gms.BaseService;
import org.microg.gms.common.GmsService;

import su.litvak.chromecast.api.v2.ChromeCast;
import su.litvak.chromecast.api.v2.ChromeCasts;
import su.litvak.chromecast.api.v2.Status;
import su.litvak.chromecast.api.v2.ChromeCastsListener;

public class CastDeviceControllerService extends BaseService {
    private static final String TAG = CastDeviceControllerService.class.getSimpleName();

    private static final Feature[] CAST_API_FEATURES = {
            new Feature("module_flag_control", 1, true),
            new Feature("analytics_proto_enum_translation", 1, true),
            new Feature("integer_to_integer_map", 1, true)
    };

    public CastDeviceControllerService() {
        super("GmsCastDeviceControllerSvc", GmsService.CAST, GmsService.CAST_API);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request,
                                     GmsService service) throws RemoteException {
        if (CastApiRequestRouting.useDeviceIndependentService(
                service == GmsService.CAST_API,
                CastDevice.getFromBundle(request.extras) != null)) {
            ConnectionInfo info = new ConnectionInfo();
            info.features = CAST_API_FEATURES;
            callback.onPostInitCompleteWithConnectionInfo(0, new CastServiceImpl(), info);
            return;
        }

        // Keep legacy device-bound CAST behavior unchanged until its separate
        // transport/connection-readiness PR is qualified.
        callback.onPostInitComplete(0,
                new CastDeviceControllerImpl(this, request.packageName, request.extras), null);
    }
}
