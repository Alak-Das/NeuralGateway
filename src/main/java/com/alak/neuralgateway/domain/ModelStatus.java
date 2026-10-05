package com.alak.neuralgateway.domain;

import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Immutable DTO representing the complete status of a model.
 * Used for API responses and SSE broadcasts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelStatus(
        String model,
        List<String> categories,
        boolean isUp,
        long latencyMs,
        Instant lastChecked,
        String errorMessage,
        List<HealthCheckResult> history,
        long totalUses,
        int activeConnections,
        double tps,
        boolean circuitOpen,
        String provider,
        int priority,
        boolean enabled
) {
    // Record automatically generates constructor, getters, equals, hashCode, toString

    /**
     * Indicates whether this status was checked within the last day. The raw
     * lastChecked timestamp remains available for clients needing a custom age.
     */
    @JsonProperty("statusFresh")
    public boolean isStatusFresh() {
        return lastChecked != null && !lastChecked.isBefore(Instant.now().minus(Duration.ofHours(24)))
                && !lastChecked.isAfter(Instant.now());
    }
}