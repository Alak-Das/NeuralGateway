package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.routing.RoutingScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final ProviderAvailabilityService providerAvailabilityService;

    // In-memory telemetry for routing score calculation
    private final Map<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> emaLatencyMap = new ConcurrentHashMap<>();

    public RoutingService(RoutingProperties properties, ModelRegistry modelRegistry,
                          CircuitBreakerService circuitBreakerService, ModelStatusProvider modelStatusProvider) {
        this(properties, modelRegistry, circuitBreakerService, modelStatusProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RoutingService(RoutingProperties properties, ModelRegistry modelRegistry,
                          CircuitBreakerService circuitBreakerService, ModelStatusProvider modelStatusProvider,
                          ProviderAvailabilityService providerAvailabilityService) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.circuitBreakerService = circuitBreakerService;
        this.modelStatusProvider = modelStatusProvider;
        this.providerAvailabilityService = providerAvailabilityService;
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
        return calculateRoutingScore(modelId, null);
    }

    private RoutingScore calculateRoutingScore(String modelId, Pipeline pipeline) {
        double emaLatency = getEmaLatency(modelId);
        int activeConnections = activeConnectionsMap.getOrDefault(modelId, new AtomicInteger(0)).get();
        Model model = modelRegistry.getModel(modelId).orElse(null);
        int priority = model != null ? (pipeline == null ? model.getPriority() : model.getPriority(pipeline)) : 1;
        return new RoutingScore(emaLatency, activeConnections, properties.getConnectionPenaltyMs(), priority);
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

        pipelineModels = pipelineModels.stream()
                .filter(model -> providerAvailabilityService == null || providerAvailabilityService.isAvailable(model.getProviderId()))
                .collect(Collectors.toList());

        // Filter by health and circuit breaker
        List<Model> healthyModels = pipelineModels.stream()
                .filter(model -> modelStatusProvider.isModelUp(model.getId()))
                .filter(model -> !circuitBreakerService.isCircuitOpen(model.getId()))
                .collect(Collectors.toList());

        // Sort by routing score (ascending - lower is better)
        healthyModels.sort((m1, m2) -> calculateRoutingScore(m1.getId(), pipeline).compareTo(calculateRoutingScore(m2.getId(), pipeline)));

        // Get fallbacks (must NOT have OPEN circuit breaker)
        List<Model> fallbackModels = pipelineModels.stream()
                .filter(model -> !healthyModels.contains(model))
                .filter(model -> !circuitBreakerService.isCircuitOpen(model.getId())) // Filter out fully OPEN
                .sorted((m1, m2) -> calculateRoutingScore(m1.getId(), pipeline).compareTo(calculateRoutingScore(m2.getId(), pipeline)))
                .collect(Collectors.toList());

        // Keep open circuits at the tail so they can receive a single half-open
        // recovery attempt when their reset window expires, after available models.
        List<Model> openCircuitFallbacks = pipelineModels.stream()
                .filter(model -> circuitBreakerService.isCircuitOpen(model.getId()))
                .sorted((m1, m2) -> calculateRoutingScore(m1.getId(), pipeline).compareTo(calculateRoutingScore(m2.getId(), pipeline)))
                .collect(Collectors.toList());

        // Combine healthy models and fallbacks. A provider-wide overload must not
        // prevent trying models registered under the other providers.
        List<Model> candidates = new ArrayList<>(healthyModels);
        int remainingSlots = properties.getMaxFallbackAttempts() - candidates.size();
        if (remainingSlots > 0) {
            candidates.addAll(fallbackModels.stream().limit(remainingSlots).collect(Collectors.toList()));
        }
        remainingSlots = properties.getMaxFallbackAttempts() - candidates.size();
        if (remainingSlots > 0) {
            candidates.addAll(openCircuitFallbacks.stream().limit(remainingSlots).collect(Collectors.toList()));
        }

        // Emergency Degraded Mode: If all circuits are OPEN or no models are healthy,
        // NEVER return an empty list and take down the entire gateway!
        // Select the top candidates by priority and lowest historical latency to act as canary probes.
        if (candidates.isEmpty() && !pipelineModels.isEmpty()) {
            List<Model> recoveryCandidates = pipelineModels.stream()
                    .sorted((m1, m2) -> calculateRoutingScore(m1.getId(), pipeline).compareTo(calculateRoutingScore(m2.getId(), pipeline)))
                    .limit(Math.max(1, properties.getMaxFallbackAttempts()))
                    .collect(Collectors.toList());
            return diversifyProviders(recoveryCandidates);
        }

        return diversifyProviders(candidates);
    }

    /**
     * Spread failover attempts across independent providers before trying a
     * second model from the same provider. This avoids multiplying a shared
     * provider outage into many equivalent failed calls.
     */
    private List<Model> diversifyProviders(List<Model> candidates) {
        Map<String, List<Model>> byProvider = new LinkedHashMap<>();
        for (Model model : candidates) {
            byProvider.computeIfAbsent(model.getProviderId(), ignored -> new ArrayList<>()).add(model);
        }
        List<Model> diversified = new ArrayList<>(candidates.size());
        boolean added;
        int index = 0;
        do {
            added = false;
            for (List<Model> providerModels : byProvider.values()) {
                if (index < providerModels.size()) {
                    diversified.add(providerModels.get(index));
                    added = true;
                }
            }
            index++;
        } while (added);
        return diversified;
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
