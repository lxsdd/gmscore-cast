/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cast;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CastDnsSdMetadataTest {
    private static Map<String, byte[]> txt(String id) {
        Map<String, byte[]> attributes = new HashMap<>();
        if (id != null) attributes.put("id", id.getBytes(StandardCharsets.UTF_8));
        return attributes;
    }

    @Test
    public void stableIdIsMandatoryButOtherTxtFieldsAreOptional() {
        assertNull(CastDnsSdMetadata.parse(null, "speaker"));
        assertNull(CastDnsSdMetadata.parse(Collections.emptyMap(), "speaker"));
        assertNull(CastDnsSdMetadata.parse(txt(""), "speaker"));
        CastDnsSdMetadata result = CastDnsSdMetadata.parse(txt("stable-id"), "speaker");
        assertNotNull(result);
        assertEquals("stable-id", result.id);
        assertEquals("speaker", result.friendlyName);
        assertEquals("", result.deviceVersion);
        assertEquals("", result.modelName);
        assertEquals("", result.iconPath);
        assertEquals(0, result.status);
        assertEquals(0, result.capabilities);
    }

    @Test
    public void missingServiceNameFallsBackToId() {
        CastDnsSdMetadata data = CastDnsSdMetadata.parse(txt("fallback-id"), null);
        assertNotNull(data);
        assertEquals("fallback-id", data.friendlyName);
    }

    @Test
    public void keepsValidSpeakerDataWithoutInventingVideo() {
        Map<String, byte[]> attrs = txt("speaker-id");
        attrs.put("fn", "Living room".getBytes(StandardCharsets.UTF_8));
        attrs.put("ca", "4".getBytes(StandardCharsets.UTF_8));
        CastDnsSdMetadata data = CastDnsSdMetadata.parse(attrs, "service");
        assertNotNull(data);
        assertEquals("Living room", data.friendlyName);
        assertEquals(4, data.capabilities);
        assertTrue(CastRouteCapabilities.isAudioOnly(data.capabilities));
    }

    @Test
    public void keepsValidTvDataAndStatusWhenPresent() {
        Map<String, byte[]> attrs = txt("tv-id");
        attrs.put("st", "2".getBytes(StandardCharsets.UTF_8));
        attrs.put("ca", "5".getBytes(StandardCharsets.UTF_8));
        CastDnsSdMetadata data = CastDnsSdMetadata.parse(attrs, "tv-service");
        assertNotNull(data);
        assertEquals(2, data.status);
        assertEquals(5, data.capabilities);
        assertFalse(CastRouteCapabilities.isAudioOnly(data.capabilities));
    }

    @Test
    public void malformedOptionalStatusDoesNotHideGroupOrSpeaker() {
        Map<String, byte[]> attrs = txt("group-id");
        attrs.put("st", "invalid".getBytes(StandardCharsets.UTF_8));
        attrs.put("ca", "36".getBytes(StandardCharsets.UTF_8));
        CastDnsSdMetadata data = CastDnsSdMetadata.parse(attrs, "group");
        assertNotNull(data);
        assertEquals(0, data.status);
        assertTrue(CastRouteCapabilities.isGroup(data.capabilities));
        attrs.put("st", "-12".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, CastDnsSdMetadata.parse(attrs, "group").status);
    }

    @Test
    public void emptyOptionalFieldsUseNonEmptyFallbackValues() {
        Map<String, byte[]> attrs = txt("known-id");
        for (String key : new String[] {"fn", "ic", "ve", "md", "st"}) {
            attrs.put(key, new byte[0]);
        }
        CastDnsSdMetadata data = CastDnsSdMetadata.parse(attrs, "service-name");
        assertNotNull(data);
        assertEquals("service-name", data.friendlyName);
        assertEquals("", data.iconPath);
        assertEquals("", data.modelName);
        assertEquals("", data.deviceVersion);
        assertEquals(0, data.status);
    }
}
