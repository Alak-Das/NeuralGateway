package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service responsible for intelligent model routing based on strict priority.
 */
@Service
public class RoutingService {

    private final RoutingProperties properties;
    private final ModelRegistry modelRegistry;
    private final ModelStatusProvider modelStatusProvider;

    // Keep active connections for telemetry only (no longer affects routing score)
    private final ConcurrentHashMap<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();

    public RoutingService(RoutingProperties properties, ModelRegistry modelRegistry,
                          ModelStatusProvider modelStatusProvider) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.modelStatusProvider = modelStatusProvider;
    }

    /**
     * Initialize telemetry maps for a model.
     */
    public void initializeModel(String modelId) {
        activeConnectionsMap.putIfAbsent(modelId, new AtomicInteger(0));
    }

    /**
     * Increment active connections for a model (when request starts).
     */
    public void incrementActiveConnections(String modelId) {
        activeConnectionsMap.computeIfAbsent(modelId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Decrement active connections for a model (when request completes).
     */
    public void decrementActiveConnections(String modelId) {
        AtomicInteger counter = activeConnectionsMap.get(modelId);
        if (counter != null) {
            counter.updateAndGet(val -> Math.max(0, val - 1));
        }
    }

    /**
     * Select the best models for a pipeline based strictly on priority and UP status.
     */
    public List<Model> selectModels(Pipeline pipeline, int estimatedTokens) {
        // Filter by enabled
        List<Model> pipelineModels = modelRegistry.getModelsByPipeline(pipeline).stream()
                .filter(Model::isEnabled)
                .collect(Collectors.toList());

        // Filter by context window if enabled
        if (properties.isContextWindowValidationEnabled()) {
            pipelineModels = pipelineModels.stream()
                    .filter(model -> model.canHandleContext(estimatedTokens))
                    .collect(Collectors.toList());
        }

        // Filter by UP status
        List<Model> healthyModels = pipelineModels.stream()
                .filter(model -> modelStatusProvider.isModelUp(model.getId()))
                .collect(Collectors.toList());

        // Sort strictly by priority (highest priority first)
        Comparator<Model> priorityOrder = Comparator
                .comparingInt((Model m) -> m.getPriority(pipeline)).reversed()
                .thenComparing(Model::getId);

        healthyModels.sort(priorityOrder);

        // Emergency degraded mode: if no healthy models, return the enabled pipeline models
        if (healthyModels.isEmpty() && !pipelineModels.isEmpty()) {
            pipelineModels.sort(priorityOrder);
            return pipelineModels;
        }

        return healthyModels;
    }

    /**
     * Get active connections count for a model.
     */
    public int getActiveConnections(String modelId) {
        return Math.max(0, activeConnectionsMap.getOrDefault(modelId, new AtomicInteger(0)).get());
    }

    /**
     * Get TPS for a model (delegates to status provider).
     */
    public double getTps(String modelId) {
        return modelStatusProvider.getTps(modelId);
    }
}
