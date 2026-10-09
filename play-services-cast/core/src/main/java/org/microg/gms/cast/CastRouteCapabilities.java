/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastDevice;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Cast DNS-SD capabilities are a bitmask, not a guessed receiver type.
 * Unknown/malformed TXT values must not silently invent video output.
 */
final class CastRouteCapabilities {
    // AndroidX MediaRouter group route type; kept as an integer because some
    // versions of the compatibility library do not expose a named constant.
    static final int MEDIA_ROUTER_DEVICE_TYPE_GROUP = 1000;
    // MediaRouter exposes UNKNOWN=0 internally but marks the constant restricted.
    private static final int MEDIA_ROUTER_DEVICE_TYPE_UNKNOWN = 0;

    private CastRouteCapabilities() {
    }

    static int fromDnsSdAttributes(Map<String, byte[]> attributes) {
        if (attributes == null) return 0;
        byte[] raw = attributes.get("ca");
        if (raw == null || raw.length == 0) return 0;
        try {
            int bits = Integer.parseInt(new String(raw, StandardCharsets.UTF_8).trim());
            return bits < 0 ? 0 : bits;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static boolean has(int bits, int flag) {
        return (bits & flag) == flag;
    }

    static boolean isAudioOnly(int bits) {
        return has(bits, CastDevice.CAPABILITY_AUDIO_OUT)
                && !has(bits, CastDevice.CAPABILITY_VIDEO_OUT);
    }

    static boolean isGroup(int bits) {
        return has(bits, CastDevice.CAPABILITY_MULTIZONE_GROUP)
                || has(bits, CastDevice.CAPABILITY_DYNAMIC_GROUP);
    }

    static int mediaRouterDeviceType(int bits) {
        if (isGroup(bits)) return MEDIA_ROUTER_DEVICE_TYPE_GROUP;
        if (isAudioOnly(bits)) return MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER;
        if (has(bits, CastDevice.CAPABILITY_VIDEO_OUT)) {
            return MediaRouter.RouteInfo.DEVICE_TYPE_TV;
        }
        return MEDIA_ROUTER_DEVICE_TYPE_UNKNOWN;
    }

    /** Preserve the legacy broad filter only when the receiver gives no output caps. */
    static boolean advertisesMimeType(int bits, String mimeType) {
        if (mimeType == null) return false;
        boolean audio = has(bits, CastDevice.CAPABILITY_AUDIO_OUT);
        boolean video = has(bits, CastDevice.CAPABILITY_VIDEO_OUT);
        if (!audio && !video) return true;
        if (mimeType.startsWith("audio/")) return audio;
        if (mimeType.startsWith("video/") || mimeType.startsWith("image/")) return video;
        return mimeType.startsWith("application/");
    }
}
