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
import java.util.stream.Collectors;

/**
 * Service responsible for managing the model catalog, pipelines, and model lookup.
 */
@Service
public class ModelRegistry {

    private final Map<String, Model> modelCatalog = new HashMap<>();
    private final Map<String, ApiKeyPool> apiKeyPools = new HashMap<>();
    private final LlmProvidersProperties properties;

    public ModelRegistry(LlmProvidersProperties properties) {
        this.properties = properties;
        initializeRegistry();
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
                            modelConfig.isEnabled()
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
}
