package com.example.llmservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for health check behavior.
 */
@Component
@ConfigurationProperties(prefix = "llm.health-check")
@Validated
public class HealthCheckProperties {

    /**
     * Interval between health check sweeps in milliseconds.
     */
    @Positive
    private long intervalMs = 300000; // 5 minutes

    /**
     * Timeout for individual model ping in milliseconds.
     */
    @Positive
    private long pingTimeoutMs = 180000; // 180 seconds

    /**
     * Minimum gap between consecutive pings in milliseconds (rate pacing).
     */
    @Positive
    private long minPingGapMs = 5000; // 5 seconds

    /**
     * Maximum tokens to request in health check ping (1 = minimal).
     */
    @Min(1)
    @Max(10)
    private int pingMaxTokens = 1;

    /**
     * Enable/disable health check scheduling.
     */
    private boolean enabled = true;

    /**
     * Prioritize models by EMA latency for ping ordering.
     */
    private boolean prioritizeByEma = true;

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getPingTimeoutMs() {
        return pingTimeoutMs;
    }

    public void setPingTimeoutMs(long pingTimeoutMs) {
        this.pingTimeoutMs = pingTimeoutMs;
    }

    public long getMinPingGapMs() {
        return minPingGapMs;
    }

    public void setMinPingGapMs(long minPingGapMs) {
        this.minPingGapMs = minPingGapMs;
    }

    public int getPingMaxTokens() {
        return pingMaxTokens;
    }

    public void setPingMaxTokens(int pingMaxTokens) {
        this.pingMaxTokens = pingMaxTokens;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPrioritizeByEma() {
        return prioritizeByEma;
    }

    public void setPrioritizeByEma(boolean prioritizeByEma) {
        this.prioritizeByEma = prioritizeByEma;
    }
}