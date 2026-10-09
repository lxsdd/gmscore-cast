/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import com.google.android.gms.cast.CastDevice;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Synthetic DNS-SD announcements, with no personal receiver data. */
public class CastRouteCapabilitiesTest {
    private static Map<String, byte[]> txt(String ca) {
        Map<String, byte[]> attributes = new HashMap<>();
        attributes.put("ca", ca.getBytes(StandardCharsets.UTF_8));
        return attributes;
    }

    @Test
    public void speakerWithoutVideoOutputRemainsAudioOnly() {
        int bits = CastRouteCapabilities.fromDnsSdAttributes(txt("4"));
        assertTrue(CastRouteCapabilities.isAudioOnly(bits));
        assertEquals(2, CastRouteCapabilities.mediaRouterDeviceType(bits)); // speaker
        assertTrue(CastRouteCapabilities.advertisesMimeType(bits, "audio/mpeg"));
        assertFalse(CastRouteCapabilities.advertisesMimeType(bits, "video/mp4"));
        assertFalse(CastRouteCapabilities.advertisesMimeType(bits, "image/jpeg"));
    }

    @Test
    public void televisionKeepsAudioAndVideoFilters() {
        int bits = CastRouteCapabilities.fromDnsSdAttributes(txt("5"));
        assertFalse(CastRouteCapabilities.isAudioOnly(bits));
        assertEquals(1, CastRouteCapabilities.mediaRouterDeviceType(bits)); // TV
        assertTrue(CastRouteCapabilities.advertisesMimeType(bits, "audio/mpeg"));
        assertTrue(CastRouteCapabilities.advertisesMimeType(bits, "video/mp4"));
        assertTrue(CastRouteCapabilities.advertisesMimeType(bits, "image/png"));
    }

    @Test
    public void staticAndDynamicSpeakerGroupsHaveGroupDeviceType() {
        int staticGroup = CastDevice.CAPABILITY_AUDIO_OUT
                | CastDevice.CAPABILITY_MULTIZONE_GROUP;
        int dynamicGroup = CastDevice.CAPABILITY_AUDIO_OUT
                | CastDevice.CAPABILITY_DYNAMIC_GROUP;
        assertTrue(CastRouteCapabilities.isGroup(staticGroup));
        assertTrue(CastRouteCapabilities.isGroup(dynamicGroup));
        assertEquals(1000, CastRouteCapabilities.mediaRouterDeviceType(staticGroup));
        assertEquals(1000, CastRouteCapabilities.mediaRouterDeviceType(dynamicGroup));
        assertTrue(CastRouteCapabilities.advertisesMimeType(dynamicGroup, "audio/ogg"));
        assertFalse(CastRouteCapabilities.advertisesMimeType(dynamicGroup, "video/webm"));
    }

    @Test
    public void multizoneSourceIsNotAutomaticallyAGroup() {
        int bits = CastDevice.CAPABILITY_AUDIO_OUT
                | CastDevice.CAPABILITY_MULTIZONE_SOURCE;
        assertFalse(CastRouteCapabilities.isGroup(bits));
        assertEquals(2, CastRouteCapabilities.mediaRouterDeviceType(bits));
    }

    @Test
    public void missingOrInvalidCapabilitiesNeverInventVideo() {
        assertEquals(0, CastRouteCapabilities.fromDnsSdAttributes(null));
        assertEquals(0, CastRouteCapabilities.fromDnsSdAttributes(Collections.emptyMap()));
        for (String invalid : new String[] {"", "n/a", "-1", "9999999999999999999"}) {
            assertEquals(0, CastRouteCapabilities.fromDnsSdAttributes(txt(invalid)));
        }
        assertEquals(0, CastRouteCapabilities.mediaRouterDeviceType(0));
        // Unknown output retains the permissive legacy filter rather than
        // hiding a device entirely until DNS-SD can supply a valid bitmask.
        assertTrue(CastRouteCapabilities.advertisesMimeType(0, "audio/wav"));
        assertTrue(CastRouteCapabilities.advertisesMimeType(0, "video/webm"));
    }
}
