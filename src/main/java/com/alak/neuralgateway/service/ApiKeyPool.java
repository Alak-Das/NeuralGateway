package com.alak.neuralgateway.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages API keys for a provider using strict Round-Robin selection.
 */
public class ApiKeyPool {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyPool.class);

    private final String providerId;
    private final List<String> keys;
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);

    public ApiKeyPool(String providerId, List<String> keys, int ignoredRateLimitRpm) {
        this.providerId = providerId;
        this.keys = (keys != null && !keys.isEmpty()) ? List.copyOf(keys) : List.of();
        log.info("Initialized ApiKeyPool for provider '{}' with {} keys (strict round-robin)", providerId, this.keys.size());
    }

    public String getProviderId() {
        return providerId;
    }

    public String getNextKey() {
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() == 1) {
            return keys.get(0);
        }
        
        // Strict Round-Robin
        int index = currentKeyIndex.getAndUpdate(i -> (i + 1) % keys.size());
        return keys.get(index);
    }
}
