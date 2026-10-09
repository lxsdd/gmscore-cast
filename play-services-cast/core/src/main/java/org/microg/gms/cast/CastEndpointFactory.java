/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import su.litvak.chromecast.api.v2.ChromeCast;

/** Creates CastV2 transports only for endpoints advertised by DNS-SD. */
final class CastEndpointFactory {
    private CastEndpointFactory() {
    }

    static boolean isValidServicePort(int servicePort) {
        return servicePort >= 1 && servicePort <= 65535;
    }

    static ChromeCast create(String address, int servicePort) {
        if (!isValidServicePort(servicePort)) {
            throw new IllegalArgumentException("Invalid Cast service port: " + servicePort);
        }
        return new ChromeCast(address, servicePort);
    }
}
