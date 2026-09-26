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
    private long intervalMs;

    /**
     * Timeout for individual model ping in milliseconds.
     */
    @Positive
    private long pingTimeoutMs;

    /**
     * Minimum gap between consecutive pings in milliseconds (rate pacing).
     */
    @Positive
    private long minPingGapMs;

    @Min(1)
    @Max(10)
    private int pingMaxTokens;

    private boolean enabled;
    private boolean prioritizeByEma;

    @Positive
    private int threadPoolSize;

    private String lockAtLeastFor;
    private String lockAtMostFor;

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

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public String getLockAtLeastFor() {
        return lockAtLeastFor;
    }

    public void setLockAtLeastFor(String lockAtLeastFor) {
        this.lockAtLeastFor = lockAtLeastFor;
    }

    public String getLockAtMostFor() {
        return lockAtMostFor;
    }

    public void setLockAtMostFor(String lockAtMostFor) {
        this.lockAtMostFor = lockAtMostFor;
    }
}