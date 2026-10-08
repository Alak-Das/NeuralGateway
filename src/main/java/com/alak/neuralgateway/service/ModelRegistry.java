package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service responsible for managing the model catalog, pipelines, and model lookup.
 */
@Service
public class ModelRegistry {

    private final Map<String, Model> modelCatalog = new ConcurrentHashMap<>();
    private final Map<String, ApiKeyPool> apiKeyPools = new ConcurrentHashMap<>();
    private final LlmProvidersProperties properties;
    private final RedisPersistenceService redisPersistenceService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ModelRegistry(LlmProvidersProperties properties, RedisPersistenceService redisPersistenceService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisPersistenceService = redisPersistenceService;
        this.objectMapper = objectMapper;
        initializeRegistry();
        loadDynamicOverrides();
    }
    
    private void initializeRegistry() {
        if (properties.getProviders() == null) {
            return;
        }

        for (Map.Entry<String, LlmProvidersProperties.ProviderConfig> entry : properties.getProviders().entrySet()) {
            String providerId = entry.getKey();
            LlmProvidersProperties.ProviderConfig providerConfig = entry.getValue();

            // Create ApiKeyPool for this provider
            ApiKeyPool pool = new ApiKeyPool(providerId, providerConfig.getKeys(), providerConfig.getRateLimitRpm());
            apiKeyPools.put(providerId, pool);

            // Register Models
            if (providerConfig.getModels() != null) {
                for (LlmProvidersProperties.ModelConfig modelConfig : providerConfig.getModels()) {
                    String modelId = modelConfig.getId();
                    if (modelId == null || modelId.trim().isEmpty()) {
                        continue;
                    }

                    Model model = new Model(
                            modelId.trim(),
                            modelId.trim(),
                            providerId,
                            modelConfig.getPipelines(),
                            modelConfig.getContextLimit(),
                            modelConfig.getPriority(),
                            modelConfig.getPipelinePriorities(),
                            determineCapabilities(modelId.trim(), modelConfig.getPipelines()),
                            modelConfig.isEnabled(),
                            modelConfig.getTimeoutMs()
                    );
                    
                    // If a model is defined multiple times (shouldn't happen in proper YAML), overwrite
                    modelCatalog.put(model.getId(), model);
                }
            }
        }
    }

    public ApiKeyPool getApiKeyPool(String providerId) {
        return apiKeyPools.get(providerId);
    }
    
    public LlmProvidersProperties.ProviderConfig getProviderConfig(String providerId) {
        if (properties.getProviders() == null) {
            return null;
        }
        return properties.getProviders().get(providerId);
    }

    private Model.ModelCapabilities determineCapabilities(String modelId, Set<Pipeline> pipelines) {
        // Default capabilities based on pipeline - can be enhanced with model-specific logic
        boolean supportsVision = pipelines != null && pipelines.contains(Pipeline.VISION);
        boolean supportsTools = true; // Assume most models support tools
        boolean supportsJsonMode = true; // Assume most models support JSON mode
        
        // Special cases can be added here
        if (modelId.contains("nemotron-3-nano-omni")) {
            // Nano omni might have different capabilities
            supportsJsonMode = false;
        }
        
        return new Model.ModelCapabilities(supportsVision, supportsTools, supportsJsonMode);
    }

    public Optional<Model> getModel(String modelId) {
        return Optional.ofNullable(modelCatalog.get(modelId));
    }

    public Set<String> getAllModelIds() {
        return Collections.unmodifiableSet(modelCatalog.keySet());
    }

    /**
     * Get the model catalog map.
     * 
     * @return unmodifiable map of model ID -> model
     */
    public Map<String, Model> getModelCatalog() {
        return Collections.unmodifiableMap(modelCatalog);
    }

    public List<Model> getModelsByPipeline(Pipeline pipeline) {
        return modelCatalog.values().stream()
                .filter(model -> model.isAvailableForPipeline(pipeline))
                .collect(Collectors.toList());
    }

    public boolean isValidModel(String modelId) {
        return modelCatalog.containsKey(modelId);
    }

    public int getModelContextLimit(String modelId) {
        Model model = modelCatalog.get(modelId);
        return model != null ? model.getContextLimit() : 32000;
    }

    public void updateModelConfig(String modelId, Map<String, Object> config) {
        Model existing = modelCatalog.get(modelId);
        if (existing == null) {
            existing = findModelRelaxed(modelId);
            if (existing != null) {
                modelId = existing.getId();
            } else {
                throw new IllegalArgumentException("Model not found: " + modelId);
            }
        }
        applyOverride(modelId, config);

        try {
            String json = objectMapper.writeValueAsString(config);
            redisPersistenceService.saveModelConfig(modelId, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save config to Redis", e);
        }
    }

    public Model findModelRelaxed(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        String trimmed = modelId.trim();
        for (Model m : modelCatalog.values()) {
            if (m.getId().equalsIgnoreCase(trimmed) || m.getId().endsWith("/" + trimmed)) {
                return m;
            }
        }
        return null;
    }

    private void loadDynamicOverrides() {
        Map<String, String> configs = redisPersistenceService.getAllModelConfigs();
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String modelId = entry.getKey();
            if (modelCatalog.containsKey(modelId)) {
                try {
                    Map<String, Object> config = objectMapper.readValue(entry.getValue(), new TypeReference<Map<String, Object>>(){});
                    applyOverride(modelId, config);
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    private void applyOverride(String modelId, Map<String, Object> config) {
        Model existing = modelCatalog.get(modelId);
        if (existing == null) return;

        boolean enabled = existing.isEnabled();
        if (config.containsKey("enabled")) {
            Object val = config.get("enabled");
            if (val instanceof Boolean b) {
                enabled = b;
            } else if (val != null) {
                enabled = Boolean.parseBoolean(String.valueOf(val));
            }
        }

        int priority = existing.getPriority();
        if (config.containsKey("priority")) {
            Object val = config.get("priority");
            if (val instanceof Number n) {
                priority = n.intValue();
            } else if (val != null) {
                try {
                    priority = Integer.parseInt(String.valueOf(val).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        
        Set<Model.Pipeline> pipelines = existing.getPipelines();
        if (config.containsKey("pipelines")) {
            Object val = config.get("pipelines");
            if (val instanceof List<?> pipelineStrs) {
                pipelines = pipelineStrs.stream()
                        .map(Object::toString)
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .filter(s -> !s.isEmpty())
                        .map(s -> {
                            try {
                                return Model.Pipeline.valueOf(s);
                            } catch (IllegalArgumentException e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
            }
        }

        Model updated = new Model(
                existing.getId(),
                existing.getName(),
                existing.getProviderId(),
                pipelines,
                existing.getContextLimit(),
                priority,
                existing.getPipelinePriorities(),
                existing.getCapabilities(),
                enabled,
                existing.getTimeoutMs()
        );
        modelCatalog.put(modelId, updated);
    }
}
