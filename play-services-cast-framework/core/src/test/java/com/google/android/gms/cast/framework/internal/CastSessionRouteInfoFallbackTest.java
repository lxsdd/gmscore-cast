/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CastSessionRouteInfoFallbackTest {
    private static final String CONFIGURED_RECEIVER = "01020304";
    private static final String CALLBACK_RECEIVER = "A1B2C3D4";
    private static final String STORED_RECEIVER = "C4D3B2A1";

    @Test
    public void nullCallbackUsesStoredNormalRouteWithoutChangingReceiver() {
        Bundle stored = new Bundle();
        TrackingSession session = new TrackingSession(stored);

        Bundle effective = CastSessionImpl.effectiveRouteInfoExtra(null, session);

        assertSame(stored, effective);
        assertTrue(session.routeInfoRead);
        assertEquals(CONFIGURED_RECEIVER,
                CastSessionLaunchRequest.receiverApplicationId(
                        CONFIGURED_RECEIVER, (String) null));
    }

    @Test
    public void nullCallbackUsesStoredCallerProvidedReceiver() {
        Bundle stored = new Bundle();
        TrackingSession session = new TrackingSession(stored);

        Bundle effective = CastSessionImpl.effectiveRouteInfoExtra(null, session);
        String resolved = CastSessionLaunchRequest.receiverApplicationId(
                CONFIGURED_RECEIVER, STORED_RECEIVER);

        assertSame(stored, effective);
        assertTrue(session.routeInfoRead);
        assertEquals(STORED_RECEIVER, resolved);
        assertTrue(CastSessionLaunchRequest.hasCallerProvidedReceiver(
                CONFIGURED_RECEIVER, resolved));
    }

    @Test
    public void nonNullCallbackWinsWithoutReadingStoredRouteInfo() {
        Bundle callback = new Bundle();
        Bundle stored = new Bundle();
        TrackingSession session = new TrackingSession(stored);

        Bundle effective = CastSessionImpl.effectiveRouteInfoExtra(callback, session);
        String callbackResolved = CastSessionLaunchRequest.receiverApplicationId(
                CONFIGURED_RECEIVER, CALLBACK_RECEIVER);
        String storedResolved = CastSessionLaunchRequest.receiverApplicationId(
                CONFIGURED_RECEIVER, STORED_RECEIVER);

        assertSame(callback, effective);
        assertFalse(session.routeInfoRead);
        assertEquals(CALLBACK_RECEIVER, callbackResolved);
        assertEquals(STORED_RECEIVER, storedResolved);
        assertFalse(storedResolved.equals(callbackResolved));
    }

    @Test
    public void nullCallbackWithInvalidStoredReceiverKeepsConfiguredReceiver() {
        Bundle stored = new Bundle();
        TrackingSession session = new TrackingSession(stored);

        Bundle effective = CastSessionImpl.effectiveRouteInfoExtra(null, session);

        assertSame(stored, effective);
        assertEquals(CONFIGURED_RECEIVER,
                CastSessionLaunchRequest.receiverApplicationId(
                        CONFIGURED_RECEIVER, "not-an-app-id"));
    }

    @Test
    public void nullCallbackAndNullStoredRouteInfoKeepNullBehavior() {
        TrackingSession session = new TrackingSession(null);

        Bundle effective = CastSessionImpl.effectiveRouteInfoExtra(null, session);

        assertNull(effective);
        assertEquals(CONFIGURED_RECEIVER,
                CastSessionLaunchRequest.receiverApplicationId(CONFIGURED_RECEIVER, effective));
    }

    private static final class TrackingSession extends SessionImpl {
        private final Bundle routeInfoExtra;
        private boolean routeInfoRead;

        TrackingSession(Bundle routeInfoExtra) {
            super("test-category", null, null);
            this.routeInfoExtra = routeInfoExtra;
        }

        @Override
        Bundle getRouteInfoExtra() {
            routeInfoRead = true;
            return routeInfoExtra;
        }
    }

}
