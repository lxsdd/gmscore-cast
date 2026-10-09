/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Validated, device-independent view of one Cast DNS-SD TXT record. */
final class CastDnsSdMetadata {
    final String id;
    final String deviceVersion;
    final String friendlyName;
    final String modelName;
    final String iconPath;
    final int status;
    final int capabilities;

    private CastDnsSdMetadata(String id, String deviceVersion, String friendlyName,
                              String modelName, String iconPath, int status, int capabilities) {
        this.id = id;
        this.deviceVersion = deviceVersion;
        this.friendlyName = friendlyName;
        this.modelName = modelName;
        this.iconPath = iconPath;
        this.status = status;
        this.capabilities = capabilities;
    }

    static CastDnsSdMetadata parse(Map<String, byte[]> attributes, String serviceName) {
        if (attributes == null) return null;
        String id = text(attributes, "id");
        // The Cast device ID is mandatory: anonymous descriptors cannot be
        // safely reconciled across speaker/group discovery and reopen.
        if (id == null || id.isEmpty()) return null;

        String version = orDefault(text(attributes, "ve"), "");
        String name = orDefault(text(attributes, "fn"),
                orDefault(serviceName, id));
        String model = orDefault(text(attributes, "md"), "");
        String icon = orDefault(text(attributes, "ic"), "");
        int status = intOrDefault(text(attributes, "st"), 0);
        int capabilities = CastRouteCapabilities.fromDnsSdAttributes(attributes);
        return new CastDnsSdMetadata(id, version, name, model, icon, status, capabilities);
    }

    private static String text(Map<String, byte[]> attributes, String key) {
        byte[] raw = attributes.get(key);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8).trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int intOrDefault(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
