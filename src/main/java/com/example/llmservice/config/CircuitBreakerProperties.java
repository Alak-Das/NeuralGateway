package com.example.llmservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for circuit breaker behavior.
 */
@Component
@ConfigurationProperties(prefix = "llm.circuit-breaker")
@Validated
public class CircuitBreakerProperties {

    /**
     * Number of consecutive failures before tripping the circuit breaker.
     */
    @Positive
    @Max(10)
    private int failureThreshold;

    /**
     * Time in milliseconds before a tripped circuit transitions to HALF_OPEN state.
     */
    @Positive
    private long resetTimeoutMs;

    /**
     * Number of successful requests in HALF_OPEN state before closing the circuit.
     */
    @Positive
    @Max(10)
    private int successThreshold;

    /**
     * Enable/disable automatic circuit recovery.
     */
    private boolean autoRecoveryEnabled;

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getResetTimeoutMs() {
        return resetTimeoutMs;
    }

    public void setResetTimeoutMs(long resetTimeoutMs) {
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public void setSuccessThreshold(int successThreshold) {
        this.successThreshold = successThreshold;
    }

    public boolean isAutoRecoveryEnabled() {
        return autoRecoveryEnabled;
    }

    public void setAutoRecoveryEnabled(boolean autoRecoveryEnabled) {
        this.autoRecoveryEnabled = autoRecoveryEnabled;
    }
}