package com.alak.neuralgateway.config;

import com.alak.neuralgateway.domain.model.Model;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmProvidersProperties {

    private Map<String, ProviderConfig> providers;

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public static class ProviderConfig {
        private String baseUrl;
        private int rateLimitRpm = 40;
        private boolean healthCheckEnabled = true;
        private long healthCheckIntervalMs = 14_400_000;
        private long healthCheckMaxBackoffMs = 3_600_000;
        private long overloadCooldownMs = 300_000;
        private long quotaCooldownMs = 86_400_000;
        private long authenticationCooldownMs = 86_400_000;
        private List<String> keys;
        private List<ModelConfig> models;
        /**
         * If true, the health check tolerates provider-qualified/canonical model IDs in the
         * upstream response (e.g. "openai/gpt-oss-120b" for requested "gpt-oss-120b").
         * Useful for aggregators like AntSeed that echo canonical IDs.
         */
        private boolean allowQualifiedModelIds = false;
        /**
         * Minimum allowed max_tokens for real (routed) requests to this provider.
         * Some providers (e.g. explabs gpt-6-luna) reject max_tokens below a threshold with a
         * 400 error. The gateway raises any client-supplied max_tokens below this floor to it.
         * 0 (default) means no floor is applied.
         */
        private int minMaxTokens = 0;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getRateLimitRpm() {
            return rateLimitRpm;
        }

        public void setRateLimitRpm(int rateLimitRpm) {
            this.rateLimitRpm = rateLimitRpm;
        }

        public boolean isHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        public void setHealthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
        }

        public long getHealthCheckIntervalMs() {
            return healthCheckIntervalMs;
        }

        public void setHealthCheckIntervalMs(long healthCheckIntervalMs) {
            this.healthCheckIntervalMs = healthCheckIntervalMs;
        }

        public long getHealthCheckMaxBackoffMs() {
            return healthCheckMaxBackoffMs;
        }

        public void setHealthCheckMaxBackoffMs(long healthCheckMaxBackoffMs) {
            this.healthCheckMaxBackoffMs = healthCheckMaxBackoffMs;
        }

        public long getOverloadCooldownMs() {
            return overloadCooldownMs;
        }

        public void setOverloadCooldownMs(long overloadCooldownMs) {
            this.overloadCooldownMs = overloadCooldownMs;
        }

        public long getQuotaCooldownMs() {
            return quotaCooldownMs;
        }

        public void setQuotaCooldownMs(long quotaCooldownMs) {
            this.quotaCooldownMs = quotaCooldownMs;
        }

        public long getAuthenticationCooldownMs() {
            return authenticationCooldownMs;
        }

        public void setAuthenticationCooldownMs(long authenticationCooldownMs) {
            this.authenticationCooldownMs = authenticationCooldownMs;
        }

        public boolean isAllowQualifiedModelIds() {
            return allowQualifiedModelIds;
        }

        public void setAllowQualifiedModelIds(boolean allowQualifiedModelIds) {
            this.allowQualifiedModelIds = allowQualifiedModelIds;
        }

        public int getMinMaxTokens() {
            return minMaxTokens;
        }

        public void setMinMaxTokens(int minMaxTokens) {
            this.minMaxTokens = minMaxTokens;
        }

        public List<String> getKeys() {
            return keys;
        }

        public void setKeys(List<String> keys) {
            this.keys = keys;
        }

        public List<ModelConfig> getModels() {
            return models;
        }

        public void setModels(List<ModelConfig> models) {
            this.models = models;
        }
    }

    public static class ModelConfig {
        private String id;
        private Set<Model.Pipeline> pipelines;
        private int priority = 1;
        private int contextLimit = 32000;
        private Map<Model.Pipeline, Integer> pipelinePriorities;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Set<Model.Pipeline> getPipelines() {
            return pipelines;
        }

        public void setPipelines(Set<Model.Pipeline> pipelines) {
            this.pipelines = pipelines;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getContextLimit() {
            return contextLimit;
        }

        public void setContextLimit(int contextLimit) {
            this.contextLimit = contextLimit;
        }

        public Map<Model.Pipeline, Integer> getPipelinePriorities() {
            return pipelinePriorities;
        }

        public void setPipelinePriorities(Map<Model.Pipeline, Integer> pipelinePriorities) {
            this.pipelinePriorities = pipelinePriorities;
        }
    }
}
