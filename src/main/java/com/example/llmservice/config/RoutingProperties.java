package com.example.llmservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for routing algorithm tuning.
 */
@Component
@ConfigurationProperties(prefix = "llm.routing")
@Validated
public class RoutingProperties {

    /**
     * Penalty in milliseconds added per active connection to the routing score.
     * Higher values discourage routing to busy models.
     */
    @Positive
    private int connectionPenaltyMs = 300;

    /**
     * Maximum number of fallback attempts when primary model fails.
     */
    @Min(1)
    @Max(10)
    private int maxFallbackAttempts = 3;

    /**
     * Enable/disable context window validation before routing.
     */
    private boolean contextWindowValidationEnabled = true;

    /**
     * Default context limit for models not explicitly configured.
     */
    @Positive
    private int defaultContextLimit = 32000;

    public int getConnectionPenaltyMs() {
        return connectionPenaltyMs;
    }

    public void setConnectionPenaltyMs(int connectionPenaltyMs) {
        this.connectionPenaltyMs = connectionPenaltyMs;
    }

    public int getMaxFallbackAttempts() {
        return maxFallbackAttempts;
    }

    public void setMaxFallbackAttempts(int maxFallbackAttempts) {
        this.maxFallbackAttempts = maxFallbackAttempts;
    }

    public boolean isContextWindowValidationEnabled() {
        return contextWindowValidationEnabled;
    }

    public void setContextWindowValidationEnabled(boolean contextWindowValidationEnabled) {
        this.contextWindowValidationEnabled = contextWindowValidationEnabled;
    }

    public int getDefaultContextLimit() {
        return defaultContextLimit;
    }

    public void setDefaultContextLimit(int defaultContextLimit) {
        this.defaultContextLimit = defaultContextLimit;
    }
}