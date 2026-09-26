package com.example.llmservice.service;

import com.example.llmservice.config.RoutingProperties;
import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import com.example.llmservice.domain.routing.RoutingScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final CircuitBreakerService circuitBreakerService;
    private final ModelStatusProvider modelStatusProvider;

    // In-memory telemetry for routing score calculation
    private final Map<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> emaLatencyMap = new ConcurrentHashMap<>();

    public RoutingService(RoutingProperties properties, ModelRegistry modelRegistry,
                          CircuitBreakerService circuitBreakerService, ModelStatusProvider modelStatusProvider) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.circuitBreakerService = circuitBreakerService;
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
            counter.decrementAndGet();
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
     * Get current EMA latency for a model.
     */
    public double getEmaLatency(String modelId) {
        return emaLatencyMap.getOrDefault(modelId, 0.0);
    }

    /**
     * Calculate routing score for a model.
     * Score = EMA Latency + (Active Connections * Connection Penalty)
     * Lower scores are better.
     */
    public RoutingScore calculateRoutingScore(String modelId) {
        double emaLatency = getEmaLatency(modelId);
        int activeConnections = activeConnectionsMap.getOrDefault(modelId, new AtomicInteger(0)).get();
        return new RoutingScore(emaLatency, activeConnections, properties.getConnectionPenaltyMs());
    }

    /**
     * Select the best model for a pipeline based on routing score, health, and context window.
     * Returns a prioritized list of candidate models (primary + fallbacks).
     */
    public List<Model> selectModels(Pipeline pipeline, int estimatedTokens) {
        List<Model> pipelineModels = modelRegistry.getModelsByPipeline(pipeline);

        // Filter by context window if enabled
        if (properties.isContextWindowValidationEnabled()) {
            pipelineModels = pipelineModels.stream()
                    .filter(model -> model.canHandleContext(estimatedTokens))
                    .collect(Collectors.toList());
        }

        // Filter by health and circuit breaker
        List<Model> healthyModels = pipelineModels.stream()
                .filter(model -> modelStatusProvider.isModelUp(model.getId()))
                .filter(model -> !circuitBreakerService.isCircuitOpen(model.getId()))
                .collect(Collectors.toList());

        // Sort by routing score (ascending - lower is better)
        healthyModels.sort(Comparator.comparingDouble(m -> calculateRoutingScore(m.getId()).getCalculatedScore()));

        // Get unhealthy/tripped models as fallbacks (also sorted by score)
        List<Model> fallbackModels = pipelineModels.stream()
                .filter(model -> !healthyModels.contains(model))
                .sorted(Comparator.comparingDouble(m -> calculateRoutingScore(m.getId()).getCalculatedScore()))
                .collect(Collectors.toList());

        // Combine: healthy first, then fallbacks up to max attempts
        List<Model> candidates = new ArrayList<>(healthyModels);
        int remainingSlots = properties.getMaxFallbackAttempts() - candidates.size();
        if (remainingSlots > 0) {
            candidates.addAll(fallbackModels.stream().limit(remainingSlots).collect(Collectors.toList()));
        }

        return candidates;
    }

    /**
     * Get active connections count for a model.
     */
    public int getActiveConnections(String modelId) {
        return activeConnectionsMap.getOrDefault(modelId, new AtomicInteger(0)).get();
    }

    /**
     * Get TPS for a model (delegates to status provider).
     */
    public double getTps(String modelId) {
        return modelStatusProvider.getTps(modelId);
    }
}