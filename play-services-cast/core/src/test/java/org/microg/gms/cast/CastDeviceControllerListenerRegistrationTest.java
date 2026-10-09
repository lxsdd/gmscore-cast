/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Registration identity is more specific than Binder identity. A Binder can be
 * unlinked and then re-registered before an already queued death callback fires.
 */
public class CastDeviceControllerListenerRegistrationTest {
    private static IBinder binder() {
        return (IBinder) Proxy.newProxyInstance(
                CastDeviceControllerListenerRegistrationTest.class.getClassLoader(),
                new Class<?>[]{IBinder.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("No binder calls expected");
                });
    }

    @Test
    public void currentRegistrationIsRecognized() {
        IBinder binder = binder();
        IBinder.DeathRecipient recipient = () -> { };
        assertTrue(CastDeviceControllerImpl.sameListenerRegistration(
                binder, recipient, 12, binder, recipient, 12));
    }

    @Test
    public void queuedDeathFromReplacedBinderCannotMatch() {
        IBinder oldBinder = binder();
        IBinder newBinder = binder();
        IBinder.DeathRecipient oldDeath = () -> { };
        IBinder.DeathRecipient newDeath = () -> { };
        assertFalse(CastDeviceControllerImpl.sameListenerRegistration(
                newBinder, newDeath, 13, oldBinder, oldDeath, 12));
    }

    @Test
    public void sameBinderReRegisteredWithNewRecipientCannotMatchOldDeath() {
        IBinder binder = binder();
        IBinder.DeathRecipient oldDeath = () -> { };
        IBinder.DeathRecipient newDeath = () -> { };
        assertFalse(CastDeviceControllerImpl.sameListenerRegistration(
                binder, newDeath, 13, binder, oldDeath, 12));
    }

    @Test
    public void cleanupCannotClearNewEpochEvenWhenBinderAndRecipientMatch() {
        IBinder binder = binder();
        IBinder.DeathRecipient recipient = () -> { };
        assertFalse(CastDeviceControllerImpl.sameListenerRegistration(
                binder, recipient, 14, binder, recipient, 13));
    }
}
