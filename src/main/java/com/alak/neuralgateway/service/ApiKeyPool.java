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
    private static final Duration AUTHENTICATION_COOLDOWN = Duration.ofMinutes(1);

    private final List<KeyEntry> keys = new ArrayList<>();
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Instant> authenticationCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cooldownFailures = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();
    private final String providerId;

    public ApiKeyPool(String providerId, List<String> rawKeys, int rateLimitRpm) {
        this.providerId = providerId;
        
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .limitForPeriod(rateLimitRpm)
                // Don't queue a user request behind a depleted key; try another key or fail over.
                .timeoutDuration(Duration.ZERO)
                .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);

        if (rawKeys != null) {
            java.util.Set<String> uniqueKeys = new java.util.LinkedHashSet<>();
            for (String key : rawKeys) {
                if (key != null) {
                    String trimmed = key.trim();
                    if (!trimmed.isEmpty()) {
                        uniqueKeys.add(trimmed);
                    }
                }
            }
            for (String trimmed : uniqueKeys) {
                RateLimiter limiter = registry.rateLimiter(providerId + "-" + trimmed.hashCode());
                keys.add(new KeyEntry(trimmed, limiter));
            }
        }
    }

    /**
     * Mark an API key as rate-limited by upstream (e.g. on HTTP 429).
     * It will be bypassed during key selection until the cooldown expires.
     */

    public void markKeyActive(String key) {
        if (key != null && !key.isEmpty()) {
            activeConnections.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    public void markKeyIdle(String key) {
        if (key != null && !key.isEmpty()) {
            AtomicInteger count = activeConnections.get(key);
            if (count != null && count.get() > 0) count.decrementAndGet();
        }
    }

    public int getActiveConnections(String key) {
        if (key == null || key.isEmpty()) return 0;
        AtomicInteger count = activeConnections.get(key);
        return count == null ? 0 : count.get();
    }
    public void recordRateLimit(String key, Duration coolDownDuration) {
        if (key != null && !key.isEmpty()) {
            int failures = cooldownFailures.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            long multiplier = 1L << Math.min(failures - 1, 10);
            long baseMillis = Math.max(1, coolDownDuration.toMillis());
            long backoffMillis = Math.min(Duration.ofMinutes(15).toMillis(), baseMillis * multiplier);
            cooldownUntil.put(key, Instant.now().plusMillis(backoffMillis));
        }
    }

    public void markKeyHealthy(String key) {
        if (key != null && !key.isEmpty()) {
            cooldownFailures.remove(key);
            cooldownUntil.remove(key);
            authenticationCooldownUntil.remove(key);
        }
    }

    /**
     * Quarantine a key rejected with 401/403 while the rest of the configured
     * key pool is tried.
     */
    public void markKeyAuthenticationFailure(String key) {
        if (key != null && !key.isEmpty()) {
            authenticationCooldownUntil.put(key, Instant.now().plus(AUTHENTICATION_COOLDOWN));
        }
    }

    public int getConfiguredKeyCount() {
        return keys.size();
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

        if (keys.stream().allMatch(entry -> isAuthenticationCoolingDown(entry.key()))) {
            throw new ProviderFailureException(
                    "All configured API keys for provider " + providerId + " were rejected by the upstream provider.",
                    401, providerId, ProviderFailureType.AUTHENTICATION, AUTHENTICATION_COOLDOWN);
        }

        int startIdx = roundRobin.getAndUpdate(i -> (i + 1) % keys.size());

        // First pass: try keys that are NOT currently cooling down AND have NO active connections
        for (int i = 0; i < keys.size(); i++) {
            int idx = (startIdx + i) % keys.size();
            KeyEntry entry = keys.get(idx);
            
            if (isAuthenticationCoolingDown(entry.key()) || isCoolingDown(entry.key())
                    || getActiveConnections(entry.key()) > 0) {
                continue;
            }

            if (entry.limiter().acquirePermission()) {
                return entry.key();
            }
        }

        // Second pass: fallback to keys that are not cooling down (even if they have active connections)
        for (int i = 0; i < keys.size(); i++) {
            int idx = (startIdx + i) % keys.size();
            KeyEntry entry = keys.get(idx);
            
            if (isAuthenticationCoolingDown(entry.key()) || isCoolingDown(entry.key())) {
                continue;
            }

            if (entry.limiter().acquirePermission()) {
                return entry.key();
            }
        }

        throw new LlmProviderClient.RateLimitException(
                "All API keys for provider " + providerId + " have exceeded their rate limits.",
                providerId, "", ""
        );
    }

    private boolean isAuthenticationCoolingDown(String key) {
        Instant until = authenticationCooldownUntil.get(key);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            authenticationCooldownUntil.remove(key, until);
            return false;
        }
        return true;
    }

    private record KeyEntry(String key, RateLimiter limiter) {}
}
