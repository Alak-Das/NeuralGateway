package com.alak.neuralgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for data retention policy.
 * Controls TTL and cleanup intervals for Redis-stored data.
 */
@Component
@ConfigurationProperties(prefix = "llm.data-retention")
public class DataRetentionProperties {

    private int ttlHours = 24;
    private int cleanupIntervalMinutes = 60;
    private int maxHistorySize = 100;

    public int getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(int ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public void setCleanupIntervalMinutes(int cleanupIntervalMinutes) {
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
    }

    public int getMaxHistorySize() {
        return maxHistorySize;
    }

    public void setMaxHistorySize(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    public Duration getTtlDuration() {
        return Duration.ofHours(ttlHours);
    }

    public Duration getCleanupInterval() {
        return Duration.ofMinutes(cleanupIntervalMinutes);
    }
}