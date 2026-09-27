
package com.alak.neuralgateway.domain.health;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class HealthCheckResult {
    private final String model;
    private final boolean isUp;
    private final long latencyMs;
    private final Instant timestamp;
    private final String errorMessage;
    private final boolean isBackgroundProbe;

    public HealthCheckResult() {
        this.model = null;
        this.isUp = false;
        this.latencyMs = 0;
        this.timestamp = null;
        this.errorMessage = null;
        this.isBackgroundProbe = false;
    }

    public HealthCheckResult(String model, boolean isUp, long latencyMs, Instant timestamp, String errorMessage) {
        this(model, isUp, latencyMs, timestamp, errorMessage, false);
    }

    @JsonCreator
    public HealthCheckResult(
            @JsonProperty("model") String model, 
            @JsonProperty("isUp") boolean isUp, 
            @JsonProperty("latencyMs") long latencyMs, 
            @JsonProperty("timestamp") Instant timestamp, 
            @JsonProperty("errorMessage") String errorMessage, 
            @JsonProperty("isBackgroundProbe") boolean isBackgroundProbe) {
        this.model = model;
        this.isUp = isUp;
        this.latencyMs = latencyMs;
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
        this.isBackgroundProbe = isBackgroundProbe;
    }

    public String getModel() { return model; }
    public boolean isUp() { return isUp; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getTimestamp() { return timestamp; }
    public String getErrorMessage() { return errorMessage; }
    
    @JsonProperty("isBackgroundProbe")
    public boolean isBackgroundProbe() { return isBackgroundProbe; }

    @Override
    public String toString() {
        return "HealthCheckResult{" +
                "model='" + model + "'" +
                ", isUp=" + isUp +
                ", latencyMs=" + latencyMs +
                ", timestamp=" + timestamp +
                ", errorMessage='" + errorMessage + "'" +
                ", isBackgroundProbe=" + isBackgroundProbe +
                "}";
    }
}

