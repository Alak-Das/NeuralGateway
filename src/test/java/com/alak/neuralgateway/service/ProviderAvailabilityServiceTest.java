package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderAvailabilityServiceTest {
    @Test
    void successfulProbeClearsStaleAuthenticationCooldown() {
        ModelRegistry registry = mock(ModelRegistry.class);
        RedisPersistenceService redis = mock(RedisPersistenceService.class);
        when(redis.getProviderUnavailableReason("nvidia")).thenReturn(ProviderFailureType.AUTHENTICATION.name());
        ProviderAvailabilityService service = new ProviderAvailabilityService(registry, redis);

        service.recordSuccess("nvidia");

        verify(redis).clearProviderUnavailable("nvidia");
    }

    @Test
    void successfulProbeDoesNotClearProviderQuotaCooldown() {
        ModelRegistry registry = mock(ModelRegistry.class);
        RedisPersistenceService redis = mock(RedisPersistenceService.class);
        when(redis.getProviderUnavailableReason("provider")).thenReturn(ProviderFailureType.QUOTA_EXHAUSTED.name());
        ProviderAvailabilityService service = new ProviderAvailabilityService(registry, redis);

        service.recordSuccess("provider");

        verify(redis, never()).clearProviderUnavailable("provider");
    }

    @Test
    void authenticationCooldownIsShortEnoughForAutomaticCredentialRecovery() {
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();

        org.junit.jupiter.api.Assertions.assertTrue(config.getAuthenticationCooldownMs() < 3_600_000);
    }
}
