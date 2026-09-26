package com.example.llmservice.service;

import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import org.springframework.beans.factory.annotation.Value;
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

    public ModelRegistry(@Value("${nvidia.reasoning-models:}") String reasoningModelsStr,
                         @Value("${nvidia.coding-models:}") String codingModelsStr,
                         @Value("${nvidia.vision-models:}") String visionModelsStr) {
        
        List<String> reasoningModels = parseModels(reasoningModelsStr);
        List<String> codingModels = parseModels(codingModelsStr);
        List<String> visionModels = parseModels(visionModelsStr);
        
        // Mock context limits for now or inject via config if needed
        Map<String, Integer> modelContextLimits = new HashMap<>();

        initializeModelCatalog(reasoningModels, Pipeline.REASONING, modelContextLimits);
        initializeModelCatalog(codingModels, Pipeline.CODING, modelContextLimits);
        initializeModelCatalog(visionModels, Pipeline.VISION, modelContextLimits);
    }
    
    private List<String> parseModels(String modelsStr) {
        if (modelsStr == null || modelsStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(modelsStr.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toList());
    }

    private void initializeModelCatalog(List<String> models, Pipeline pipeline, Map<String, Integer> contextLimits) {
        for (String modelId : models) {
            String trimmedModelId = Objects.requireNonNullElse(modelId, "").trim();
            if (trimmedModelId.isEmpty()) {
                continue;
            }
            
            Integer contextLimit = contextLimits.getOrDefault(trimmedModelId, 32000); // DEFAULT_CONTEXT_LIMIT
            Model model = new Model(
                    trimmedModelId,
                    trimmedModelId,
                    pipeline,
                    contextLimit,
                    determineCapabilities(trimmedModelId, pipeline)
            );
            modelCatalog.put(trimmedModelId, model);
        }
    }

    private Model.ModelCapabilities determineCapabilities(String modelId, Pipeline pipeline) {
        // Default capabilities based on pipeline - can be enhanced with model-specific logic
        boolean supportsVision = pipeline == Pipeline.VISION;
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
                .filter(model -> model.getPipeline() == pipeline)
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