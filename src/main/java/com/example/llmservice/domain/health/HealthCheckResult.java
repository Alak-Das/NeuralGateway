package com.example.llmservice.domain.health;

import java.time.Instant;

/**
 * Value object representing the result of a health check ping to a model.
 */
public class HealthCheckResult {
    private final String model;
    private final boolean isUp;
    private final long latencyMs;
    private final Instant timestamp;
    private final String errorMessage;

    public HealthCheckResult(String model, boolean isUp, long latencyMs, Instant timestamp, String errorMessage) {
        this.model = model;
        this.isUp = isUp;
        this.latencyMs = latencyMs;
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public boolean isUp() {
        return isUp;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "HealthCheckResult{" +
                "model='" + model + '\'' +
                ", isUp=" + isUp +
                ", latencyMs=" + latencyMs +
                ", timestamp=" + timestamp +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}