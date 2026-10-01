package com.alak.neuralgateway;

import com.alak.neuralgateway.service.ApiKeyPool;
import com.alak.neuralgateway.service.LlmProviderClient.RateLimitException;
import com.alak.neuralgateway.service.ProviderFailureException;
import com.alak.neuralgateway.service.ProviderFailureType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ApiKeyPoolTest {

    @Test
    void testSingleKeyNormalUsage() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1"), 60);
        String key = pool.getAvailableKey();
        assertEquals("key-1", key);
    }

    @Test
    void testKeyCooldownBypassesRateLimitedKey() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1", "key-2"), 60);
        
        // Put key-1 in cooldown
        pool.recordRateLimit("key-1", Duration.ofMinutes(1));
        assertTrue(pool.isCoolingDown("key-1"));
        assertFalse(pool.isCoolingDown("key-2"));

        // Next key should be key-2
        String key = pool.getAvailableKey();
        assertEquals("key-2", key);
    }

    @Test
    void testExhaustedKeysThrowsRateLimitException() {
        // Pool with 1 request per minute limit and 0ms timeout (immediate fail)
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1"), 1);
        
        // First call succeeds
        String key = pool.getAvailableKey();
        assertEquals("key-1", key);

        // Next calls will exceed rate limit (after Resilience4j 2s wait)
        // RateLimitException must be thrown instead of generic IllegalStateException
        assertThrows(RateLimitException.class, () -> {
            pool.getAvailableKey();
        });
    }

    @Test
    void testDuplicateKeysAreFiltered() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("dup-key", "dup-key", "  dup-key  "), 60);
        assertEquals("dup-key", pool.getAvailableKey());
        assertEquals("dup-key", pool.getAvailableKey());
    }

    @Test
    void authenticationFailureQuarantinesOnlyRejectedKey() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1", "key-2"), 60);
        pool.markKeyAuthenticationFailure("key-1");

        assertEquals("key-2", pool.getAvailableKey());
    }

    @Test
    void allAuthenticationRejectedKeysReportProviderAuthenticationFailure() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1", "key-2"), 60);
        pool.markKeyAuthenticationFailure("key-1");
        pool.markKeyAuthenticationFailure("key-2");

        ProviderFailureException failure = assertThrows(ProviderFailureException.class, pool::getAvailableKey);
        assertEquals(ProviderFailureType.AUTHENTICATION, failure.getFailureType());
        assertEquals("test-provider", failure.getProviderId());
    }

    @Test
    void successfulKeyClearsItsAuthenticationCooldown() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key-1"), 60);
        pool.markKeyAuthenticationFailure("key-1");
        pool.markKeyHealthy("key-1");

        assertEquals("key-1", pool.getAvailableKey());
    }
}
