package com.alak.neuralgateway.config;

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
    @Max(20)
    private int maxFallbackAttempts = 20;

    /**
     * Enable/disable context window validation before routing.
     */
    private boolean contextWindowValidationEnabled = false;

    /**
     * Default context limit for models not explicitly configured.
     */
    @Positive
    private int defaultContextLimit = 32000;

    /**
     * Cooldown in seconds applied to a provider when a rate-limit (429) occurs.
     */
    @Positive
    private int rateLimitCooldownSeconds = 30;

    /**
     * Cooldown in seconds applied to a provider when consecutive 5xx errors occur.
     */
    @Positive
    private int providerErrorCooldownSeconds = 60;

    /**
     * Number of consecutive upstream errors before putting provider in cooldown.
     */
    @Positive
    private int consecutiveErrorThreshold = 3;

    /**
     * Approximate characters per token for heuristic token estimation.
     */
    @Positive
    private double charsPerToken = 3.5;

    /**
     * Fixed token budget assigned per multimodal image payload.
     */
    @Positive
    private int tokensPerImage = 2048;

    /**
     * Default output tokens assumed when client does not supply max_tokens.
     */
    @Positive
    private int defaultOutputTokens = 4096;

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

    public int getRateLimitCooldownSeconds() {
        return rateLimitCooldownSeconds;
    }

    public void setRateLimitCooldownSeconds(int rateLimitCooldownSeconds) {
        this.rateLimitCooldownSeconds = rateLimitCooldownSeconds;
    }

    public int getProviderErrorCooldownSeconds() {
        return providerErrorCooldownSeconds;
    }

    public void setProviderErrorCooldownSeconds(int providerErrorCooldownSeconds) {
        this.providerErrorCooldownSeconds = providerErrorCooldownSeconds;
    }

    public int getConsecutiveErrorThreshold() {
        return consecutiveErrorThreshold;
    }

    public void setConsecutiveErrorThreshold(int consecutiveErrorThreshold) {
        this.consecutiveErrorThreshold = consecutiveErrorThreshold;
    }

    public double getCharsPerToken() {
        return charsPerToken;
    }

    public void setCharsPerToken(double charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    public int getTokensPerImage() {
        return tokensPerImage;
    }

    public void setTokensPerImage(int tokensPerImage) {
        this.tokensPerImage = tokensPerImage;
    }

    public int getDefaultOutputTokens() {
        return defaultOutputTokens;
    }

    public void setDefaultOutputTokens(int defaultOutputTokens) {
        this.defaultOutputTokens = defaultOutputTokens;
    }
}
