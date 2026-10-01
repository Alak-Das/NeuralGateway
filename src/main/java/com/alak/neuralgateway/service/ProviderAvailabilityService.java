package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks provider-wide availability independently from model health. A shared
 * quota or capacity error should prevent model hopping within that provider.
 */
@Service
public class ProviderAvailabilityService {
    private final ModelRegistry modelRegistry;
    private final RedisPersistenceService redisPersistence;

    public ProviderAvailabilityService(ModelRegistry modelRegistry, RedisPersistenceService redisPersistence) {
        this.modelRegistry = modelRegistry;
        this.redisPersistence = redisPersistence;
    }

    public boolean isAvailable(String providerId) {
        return redisPersistence.getProviderUnavailableReason(providerId) == null;
    }

    public void recordFailure(ProviderFailureException failure) {
        if (!failure.isProviderWide()) return;
        LlmProvidersProperties.ProviderConfig config = modelRegistry.getProviderConfig(failure.getProviderId());
        if (config == null) return;

        Duration configured = switch (failure.getFailureType()) {
            case QUOTA_EXHAUSTED -> Duration.ofMillis(config.getQuotaCooldownMs());
            case AUTHENTICATION -> Duration.ofMillis(config.getAuthenticationCooldownMs());
            default -> Duration.ofMillis(config.getOverloadCooldownMs());
        };
        Duration cooldown = failure.getRetryAfter() != null && !failure.getRetryAfter().isNegative()
                ? failure.getRetryAfter().compareTo(configured) > 0 ? failure.getRetryAfter() : configured
                : configured;
        redisPersistence.setProviderUnavailable(failure.getProviderId(), failure.getFailureType().name(), cooldown);
    }

    public void recordSuccess(String providerId) {
        String reason = redisPersistence.getProviderUnavailableReason(providerId);
        if (reason != null && !ProviderFailureType.QUOTA_EXHAUSTED.name().equals(reason)) {
            redisPersistence.clearProviderUnavailable(providerId);
        }
    }

    public Map<String, String> unavailableReasons(Collection<String> providerIds) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String providerId : providerIds) {
            String reason = redisPersistence.getProviderUnavailableReason(providerId);
            if (reason != null) reasons.put(providerId, reason);
        }
        return reasons;
    }
}
