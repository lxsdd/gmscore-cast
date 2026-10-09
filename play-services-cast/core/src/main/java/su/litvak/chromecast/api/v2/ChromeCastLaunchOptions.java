/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package su.litvak.chromecast.api.v2;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.annotate.JsonSerialize;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Adds the receiver-protocol launch fields supported by modern Cast senders to the pinned
 * chromecast-java-api-v2 transport. The dependency's public launchApp API only sends appId.
 */
public final class ChromeCastLaunchOptions {
    private static final String DEFAULT_RECEIVER_ID = "receiver-0";
    private static final String RECEIVER_NAMESPACE = "urn:x-cast:com.google.cast.receiver";

    private ChromeCastLaunchOptions() {
    }

    public static boolean hasWireOptions(String language, boolean androidReceiverCompatible) {
        return language != null && !language.isEmpty() || androidReceiverCompatible;
    }

    public static Application launchApp(ChromeCast chromeCast, String applicationId,
            String language, boolean androidReceiverCompatible) throws IOException {
        if (!hasWireOptions(language, androidReceiverCompatible)) {
            return chromeCast.launchApp(applicationId);
        }

        LaunchResponse response = channel(chromeCast).sendGenericRequest(
                DEFAULT_RECEIVER_ID,
                RECEIVER_NAMESPACE,
                new LaunchRequest(applicationId, language, androidReceiverCompatible),
                LaunchResponse.class
        );
        if (response == null) {
            return null;
        }
        if ("LAUNCH_ERROR".equals(response.responseType)) {
            throw new ChromeCastException("Application launch error: " + response.reason);
        }
        if ("INVALID_REQUEST".equals(response.responseType)) {
            throw new ChromeCastException("Invalid request: " + response.reason);
        }
        return response.status == null ? null : response.status.getRunningApp();
    }

    private static Channel channel(ChromeCast chromeCast) throws IOException {
        try {
            Method method = ChromeCast.class.getDeclaredMethod("channel");
            method.setAccessible(true);
            return (Channel) method.invoke(chromeCast);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IOException("Unable to access the pinned Cast transport channel", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("Unable to open the pinned Cast transport channel", cause);
        }
    }

    @JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
    static final class LaunchRequest implements Request {
        @JsonProperty
        final String type = "LAUNCH";
        @JsonProperty
        final String appId;
        @JsonProperty
        final String language;
        @JsonProperty
        final List<String> supportedAppTypes;
        private Long requestId;

        LaunchRequest(String appId, String language, boolean androidReceiverCompatible) {
            this.appId = appId;
            this.language = language == null || language.isEmpty() ? null : language;
            this.supportedAppTypes = androidReceiverCompatible
                    ? Arrays.asList("WEB", "ANDROID_TV") : null;
        }

        @Override
        public Long getRequestId() {
            return requestId;
        }

        @Override
        public void setRequestId(Long requestId) {
            this.requestId = requestId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LaunchResponse implements Response {
        @JsonProperty
        String responseType;
        @JsonProperty
        String reason;
        @JsonProperty
        Status status;
        private Long requestId;

        public LaunchResponse() {
        }

        @Override
        public Long getRequestId() {
            return requestId;
        }

        @Override
        public void setRequestId(Long requestId) {
            this.requestId = requestId;
        }
    }
}
