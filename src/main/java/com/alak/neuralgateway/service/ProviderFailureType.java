package com.alak.neuralgateway.service;

/**
 * Describes the scope of an upstream failure so routing can avoid repeating the
 * same failed provider request under a different model name.
 */
public enum ProviderFailureType {
    RATE_LIMIT,
    PROVIDER_OVERLOAD,
    QUOTA_EXHAUSTED,
    MODEL_UNAVAILABLE,
    AUTHENTICATION,
    TRANSIENT_UPSTREAM
}
