/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package su.litvak.chromecast.api.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

public class ChromeCastLaunchOptionsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void nullOrEmptyOptionsKeepTheLegacyRequestPath() {
        assertFalse(ChromeCastLaunchOptions.hasWireOptions(null, false));
        assertFalse(ChromeCastLaunchOptions.hasWireOptions("", false));
    }

    @Test
    public void languageAddsOnlyTheProvenReceiverLanguageField() throws Exception {
        ChromeCastLaunchOptions.LaunchRequest request =
                new ChromeCastLaunchOptions.LaunchRequest("233637DE", "de-DE", false);
        request.setRequestId(17L);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertEquals("LAUNCH", json.get("type").asText());
        assertEquals("233637DE", json.get("appId").asText());
        assertEquals("de-DE", json.get("language").asText());
        assertEquals(17L, json.get("requestId").asLong());
        assertFalse(json.has("supportedAppTypes"));
        assertFalse(json.has("appParams"));
        assertFalse(json.has("credentialsData"));
        assertFalse(json.has("relaunchIfRunning"));
    }

    @Test
    public void androidReceiverCompatibilityKeepsWebAndAddsAndroidTv() throws Exception {
        ChromeCastLaunchOptions.LaunchRequest request =
                new ChromeCastLaunchOptions.LaunchRequest("233637DE", null, true);
        request.setRequestId(18L);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertTrue(ChromeCastLaunchOptions.hasWireOptions(null, true));
        assertEquals(2, json.get("supportedAppTypes").size());
        assertEquals("WEB", json.get("supportedAppTypes").get(0).asText());
        assertEquals("ANDROID_TV", json.get("supportedAppTypes").get(1).asText());
        assertFalse(json.has("language"));
    }
}
