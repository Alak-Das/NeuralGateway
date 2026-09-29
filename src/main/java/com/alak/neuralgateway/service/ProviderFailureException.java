package com.alak.neuralgateway.service;

import java.time.Duration;

/** Upstream failure enriched with the provider-wide routing consequence. */
public class ProviderFailureException extends LlmProviderClient.UpstreamServiceException {
    private final String providerId;
    private final ProviderFailureType failureType;
    private final Duration retryAfter;

    public ProviderFailureException(String message, int statusCode, String providerId,
                                    ProviderFailureType failureType, Duration retryAfter) {
        super(message, statusCode);
        this.providerId = providerId;
        this.failureType = failureType;
        this.retryAfter = retryAfter;
    }

    public String getProviderId() { return providerId; }
    public ProviderFailureType getFailureType() { return failureType; }
    public Duration getRetryAfter() { return retryAfter; }

    public boolean isProviderWide() {
        return failureType == ProviderFailureType.RATE_LIMIT
                || failureType == ProviderFailureType.PROVIDER_OVERLOAD
                || failureType == ProviderFailureType.QUOTA_EXHAUSTED
                || failureType == ProviderFailureType.AUTHENTICATION;
    }
}
