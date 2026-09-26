package com.alak.neuralgateway.service;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a pool of API keys for a specific provider.
 * Uses Resilience4j RateLimiters to enforce RPM limits per key, tracks upstream 429
 * cooldowns, and routes requests to an available key.
 */
public class ApiKeyPool {

    private final List<KeyEntry> keys = new ArrayList<>();
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final String providerId;

    public ApiKeyPool(String providerId, List<String> rawKeys, int rateLimitRpm) {
        this.providerId = providerId;
        
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .limitForPeriod(rateLimitRpm)
                // Block for up to 2 seconds if no tokens are immediately available
                .timeoutDuration(Duration.ofSeconds(2))
                .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);

        if (rawKeys != null) {
            for (String key : rawKeys) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    RateLimiter limiter = registry.rateLimiter(providerId + "-" + trimmed.hashCode());
                    keys.add(new KeyEntry(trimmed, limiter));
                }
            }
        }
    }

    /**
     * Mark an API key as rate-limited by upstream (e.g. on HTTP 429).
     * It will be bypassed during key selection until the cooldown expires.
     */
    public void recordRateLimit(String key, Duration coolDownDuration) {
        if (key != null && !key.isEmpty()) {
            cooldownUntil.put(key, Instant.now().plus(coolDownDuration));
        }
    }

    public boolean isCoolingDown(String key) {
        Instant until = cooldownUntil.get(key);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            cooldownUntil.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Finds an available API key that has not exceeded its rate limit or entered cooldown.
     * May block for up to the configured timeoutDuration if all keys are temporarily exhausted.
     * @return an available API key, or empty string if no keys configured.
     * @throws LlmProviderClient.RateLimitException if all keys are fully rate-limited even after waiting.
     */
    public String getAvailableKey() {
        if (keys.isEmpty()) {
            return ""; // Allow providers that don't need authentication
        }

        int startIdx = roundRobin.getAndUpdate(i -> (i + 1) % keys.size());

        // First pass: try keys that are NOT currently cooling down
        for (int i = 0; i < keys.size(); i++) {
            int idx = (startIdx + i) % keys.size();
            KeyEntry entry = keys.get(idx);
            
            if (isCoolingDown(entry.key())) {
                continue;
            }

            if (entry.limiter().acquirePermission()) {
                return entry.key();
            }
        }

        // Second pass: if all keys are cooling down or busy, attempt any key
        for (int i = 0; i < keys.size(); i++) {
            int idx = (startIdx + i) % keys.size();
            KeyEntry entry = keys.get(idx);
            if (entry.limiter().acquirePermission()) {
                return entry.key();
            }
        }

        throw new LlmProviderClient.RateLimitException(
                "All API keys for provider " + providerId + " have exceeded their rate limits.",
                providerId, "", ""
        );
    }

    private record KeyEntry(String key, RateLimiter limiter) {}
}
