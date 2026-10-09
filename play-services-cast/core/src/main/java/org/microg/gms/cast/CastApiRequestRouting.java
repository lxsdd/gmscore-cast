/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

/** Isolate CAST_API binder selection from Android parcel/Binder implementation. */
final class CastApiRequestRouting {
    private CastApiRequestRouting() {
    }

    static boolean useDeviceIndependentService(boolean castApi, boolean hasDevice) {
        return castApi && !hasDevice;
    }
}
