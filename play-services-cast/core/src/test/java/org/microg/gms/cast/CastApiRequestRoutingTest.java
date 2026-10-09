/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CastApiRequestRoutingTest {
    @Test
    public void castApiWithoutDeviceUsesModuleBinder() {
        assertTrue(CastApiRequestRouting.useDeviceIndependentService(true, false));
    }

    @Test
    public void legacyCastKeepsDeviceBoundController() {
        assertFalse(CastApiRequestRouting.useDeviceIndependentService(false, true));
        assertFalse(CastApiRequestRouting.useDeviceIndependentService(false, false));
    }

    @Test
    public void castApiWithDevicePreservesDeviceBoundCompatibility() {
        assertFalse(CastApiRequestRouting.useDeviceIndependentService(true, true));
    }
}
