/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;

import java.util.regex.Pattern;

/** Resolves an optional caller-provided receiver application for one selected route. */
final class CastSessionLaunchRequest {
    static final String EXTRA_RECEIVER_APPLICATION_ID =
            "com.google.android.gms.cast.EXTRA_CALLER_RECEIVER_APPLICATION_ID";

    private static final Pattern APPLICATION_ID = Pattern.compile("[0-9A-Fa-f]{8}");

    private CastSessionLaunchRequest() {
    }

    static String receiverApplicationId(String configuredApplicationId, Bundle routeInfoExtra) {
        String requestedApplicationId = routeInfoExtra == null
                ? null : routeInfoExtra.getString(EXTRA_RECEIVER_APPLICATION_ID);
        return receiverApplicationId(configuredApplicationId, requestedApplicationId);
    }

    static String receiverApplicationId(String configuredApplicationId,
            String requestedApplicationId) {
        if (requestedApplicationId == null
                || !APPLICATION_ID.matcher(requestedApplicationId).matches()) {
            return configuredApplicationId;
        }
        return requestedApplicationId;
    }

    static boolean hasCallerProvidedReceiver(String configuredApplicationId,
            String resolvedApplicationId) {
        return resolvedApplicationId != null
                && !resolvedApplicationId.equals(configuredApplicationId);
    }
}
