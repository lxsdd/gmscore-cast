/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import su.litvak.chromecast.api.v2.ChromeCast;

public class CastEndpointFactoryTest {
    @Test
    public void constructsTransportWithAdvertisedDefaultPort() {
        ChromeCast cast = CastEndpointFactory.create("192.0.2.10", 8009);

        assertEquals("192.0.2.10", cast.getAddress());
        assertEquals(8009, cast.getPort());
    }

    @Test
    public void constructsTransportWithAdvertisedNondefaultPort() {
        ChromeCast cast = CastEndpointFactory.create("192.0.2.10", 9443);

        assertEquals("192.0.2.10", cast.getAddress());
        assertEquals(9443, cast.getPort());
    }

    @Test
    public void rejectsInvalidDiscoveryPortsWithoutFallback() {
        assertFalse(CastEndpointFactory.isValidServicePort(0));
        assertFalse(CastEndpointFactory.isValidServicePort(65536));
        assertTrue(CastEndpointFactory.isValidServicePort(1));
        assertTrue(CastEndpointFactory.isValidServicePort(65535));
        assertThrows(IllegalArgumentException.class,
                () -> CastEndpointFactory.create("192.0.2.10", 0));
        assertThrows(IllegalArgumentException.class,
                () -> CastEndpointFactory.create("192.0.2.10", 65536));
    }
}
