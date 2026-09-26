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
        private List<String> keys;
        private List<ModelConfig> models;

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
    }
}
