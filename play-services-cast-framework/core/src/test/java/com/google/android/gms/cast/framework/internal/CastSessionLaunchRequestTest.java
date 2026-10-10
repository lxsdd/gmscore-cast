/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CastSessionLaunchRequestTest {
    @Test
    public void absentCallerRequestKeepsConfiguredReceiver() {
        assertEquals("233637DE", CastSessionLaunchRequest.receiverApplicationId(
                "233637DE", (String) null));
    }

    @Test
    public void validCallerRequestOverridesOneLaunch() {
        String resolved = CastSessionLaunchRequest.receiverApplicationId("233637DE", "CC1AD845");
        assertEquals("CC1AD845", resolved);
        assertTrue(CastSessionLaunchRequest.hasCallerProvidedReceiver("233637DE", resolved));
    }

    @Test
    public void malformedCallerRequestCannotChangeReceiver() {
        assertEquals("233637DE", CastSessionLaunchRequest.receiverApplicationId(
                "233637DE", "not-an-app-id"));
        assertFalse(CastSessionLaunchRequest.hasCallerProvidedReceiver(
                "233637DE", "233637DE"));
    }
}
