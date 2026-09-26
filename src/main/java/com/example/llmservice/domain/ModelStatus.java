package com.example.llmservice.domain;

import com.example.llmservice.domain.health.HealthCheckResult;

import java.time.Instant;
import java.util.List;

/**
 * Immutable DTO representing the complete status of a model.
 * Used for API responses and SSE broadcasts.
 */
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
        boolean circuitOpen
) {
    // Record automatically generates constructor, getters, equals, hashCode, toString
}