package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.routing.RoutingScore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service responsible for intelligent model routing based on latency, connections, and health.
 */
@Service
public class RoutingService {

    private final RoutingProperties properties;
    private final ModelRegistry modelRegistry;
    private final ModelStatusProvider modelStatusProvider;

    // In-memory telemetry for routing score calculation
    private final Map<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> emaLatencyMap = new ConcurrentHashMap<>();

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
        emaLatencyMap.putIfAbsent(modelId, 0.0);
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
     * Update EMA latency for a model.
     * Uses alpha = 0.1 for smoothing.
     */
    public void updateEmaLatency(String modelId, long latencyMs) {
        double currentEma = emaLatencyMap.getOrDefault(modelId, 0.0);
        double alpha = 0.1;
        double newEma = (currentEma == 0.0) ? latencyMs : (alpha * latencyMs + (1 - alpha) * currentEma);
        emaLatencyMap.put(modelId, newEma);
    }

    /**
     * Get current EMA latency for a model. Falls back to modelStatusProvider if no live EMA exists yet.
     */
    public double getEmaLatency(String modelId) {
        Double ema = emaLatencyMap.get(modelId);
        if (ema != null && ema > 0.0) {
            return ema;
        }
        if (modelStatusProvider != null) {
            long latency = modelStatusProvider.getLatency(modelId);
            if (latency > 0) {
                return (double) latency;
            }
        }
        return 0.0;
    }

    /**
     * Calculate routing score for a model.
     * Score = EMA Latency + (Active Connections * Connection Penalty)
     * Lower scores are better.
     */
    public RoutingScore calculateRoutingScore(String modelId) {
        double emaLatency = getEmaLatency(modelId);
        int activeConnections = activeConnectionsMap.getOrDefault(modelId, new AtomicInteger(0)).get();
        Model model = modelRegistry.getModel(modelId).orElse(null);
        int priority = model != null ? model.getPriority() : 1;
        return new RoutingScore(emaLatency, activeConnections, properties.getConnectionPenaltyMs(), priority);
    }

    /**
     * Select models for a pipeline based on health status and priority.
     * Returns a prioritized list of candidate models sorted by priority (highest first).
     */
    public List<Model> selectModels(Pipeline pipeline, int estimatedTokens) {
        // Disabled models are never eligible for routing.
        List<Model> pipelineModels = modelRegistry.getModelsByPipeline(pipeline).stream()
                .filter(Model::isEnabled)
                .collect(Collectors.toList());

        // Filter by context window if enabled
        if (properties.isContextWindowValidationEnabled()) {
            pipelineModels = pipelineModels.stream()
                    .filter(model -> model.canHandleContext(estimatedTokens))
                    .collect(Collectors.toList());
        }

        // Filter by health status only (no circuit breaker check)
        List<Model> healthyModels = pipelineModels.stream()
                .filter(model -> modelStatusProvider.isModelUp(model.getId()))
                .collect(Collectors.toList());

        Comparator<Model> routingOrder = Comparator
                .comparing((Model model) -> calculateRoutingScore(model, pipeline))
                .thenComparing(Model::getId);

        // Priority leads the routing score; active connections and latency break ties.
        healthyModels.sort(routingOrder);

        int maxCandidates = Math.max(1, properties.getMaxFallbackAttempts());
        List<Model> candidates = healthyModels.stream()
                .limit(maxCandidates)
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        int remainingFallbackSlots = properties.getMaxFallbackAttempts() - candidates.size();
        if (remainingFallbackSlots > 0) {
            List<Model> fallbackModels = pipelineModels.stream()
                    .filter(model -> !healthyModels.contains(model))
                    .sorted(routingOrder)
                    .limit(remainingFallbackSlots)
                    .toList();
            candidates.addAll(fallbackModels);
        }

        // Emergency degraded mode still offers bounded enabled candidates when
        // every eligible model is currently reported unhealthy.
        if (candidates.isEmpty() && !pipelineModels.isEmpty()) {
            pipelineModels.sort(routingOrder);
            return pipelineModels.stream()
                    .limit(maxCandidates)
                    .collect(Collectors.toList());
        }
        return candidates;
    }

    private RoutingScore calculateRoutingScore(Model model, Pipeline pipeline) {
        return new RoutingScore(
                getEmaLatency(model.getId()),
                getActiveConnections(model.getId()),
                properties.getConnectionPenaltyMs(),
                model.getPriority(pipeline));
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
