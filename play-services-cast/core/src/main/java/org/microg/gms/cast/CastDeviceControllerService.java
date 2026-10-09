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

import android.os.RemoteException;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.common.Feature;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;

import org.microg.gms.BaseService;
import org.microg.gms.common.GmsService;

public class CastDeviceControllerService extends BaseService {
    private static final Feature[] CAST_FEATURES = {
            new Feature("cxless_client_minimal", 1, true)
    };
    private static final Feature[] CAST_API_FEATURES = {
            new Feature("module_flag_control", 1, true),
            new Feature("analytics_proto_enum_translation", 1, true),
            new Feature("integer_to_integer_map", 1, true)
    };

    public CastDeviceControllerService() {
        // Modern clients use the same Android service entry point for both the device-bound
        // controller (CAST) and the device-independent capability service (CAST_API).
        super("GmsCastDeviceControllerSvc", GmsService.CAST, GmsService.CAST_API);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request, GmsService service) throws RemoteException {
        ConnectionInfo info = new ConnectionInfo();
        CastDevice requestedDevice = CastDevice.getFromBundle(request.extras);
        if (service == GmsService.CAST_API && requestedDevice == null) {
            // CAST_API is a device-independent capability service. In particular, current clients
            // query FLAG_ENABLE_CONNECT_WITH_OPTIONS here before creating a separate, device-bound
            // CAST request. Older clients may provide a device directly on CAST_API; keep routing
            // those requests to the connectionless device controller below.
            info.features = CAST_API_FEATURES;
            callback.onPostInitCompleteWithConnectionInfo(0, new CastServiceImpl(), info);
            return;
        }
        // A device-bound CAST service must not pretend to have connected without a device.
        // Return a failure rather than constructing a transport with a missing endpoint.
        if (requestedDevice == null) {
            info.features = CAST_FEATURES;
            callback.onPostInitCompleteWithConnectionInfo(
                    CommonStatusCodes.NETWORK_ERROR, null, info);
            return;
        }
        // Only advertise the connectionless handshake implemented by CastDeviceControllerImpl.
        // connectWithOptions is intentionally not advertised.
        info.features = CAST_FEATURES;
        CastDeviceControllerImpl controller =
                new CastDeviceControllerImpl(this, request.packageName, request.extras);

        if (!controller.hasInitialListener()) {
            // Connectionless clients receive the Binder first, then set their listener, call
            // connect(), and wait for onConnectedWithResult before treating the receiver as ready.
            callback.onPostInitCompleteWithConnectionInfo(0, controller, info);
            return;
        }

        // Legacy Cast.API clients put the listener into the service request and interpret successful
        // service init as an already established device connection. Never report success before the
        // CastV2 transport has actually connected.
        int statusCode = controller.connectBeforeInit();
        if (statusCode == 0) {
            callback.onPostInitCompleteWithConnectionInfo(0, controller, info);
        } else {
            controller.disconnect();
            callback.onPostInitCompleteWithConnectionInfo(statusCode, null, info);
        }
    }
}
