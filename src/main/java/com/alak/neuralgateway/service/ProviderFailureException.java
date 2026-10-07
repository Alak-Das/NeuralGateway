package com.alak.neuralgateway.service;

/** Upstream failure enriched with the provider-wide routing consequence. */
public class ProviderFailureException extends LlmProviderClient.UpstreamServiceException {
    private final String providerId;
    private final ProviderFailureType failureType;

    public ProviderFailureException(String message, int statusCode, String providerId,
                                    ProviderFailureType failureType) {
        super(message, statusCode);
        this.providerId = providerId;
        this.failureType = failureType;
    }

    public String getProviderId() { return providerId; }
    public ProviderFailureType getFailureType() { return failureType; }
}
