package com.example.llmservice.service;

import com.example.llmservice.domain.health.HealthCheckResult;
import com.example.llmservice.domain.ModelStatus;
import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service managing model status - implements both provider and updater interfaces.
 * Aggregates health data, telemetry, and Redis-persisted state into ModelStatus DTOs.
 */
@Service
public class ModelStatusService implements ModelStatusProvider, ModelStatusUpdater {

    private final RedisPersistenceService redisPersistence;
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper;

    // In-memory status cache for fast reads
    private final Map<String, ModelStatus> statusCache = new ConcurrentHashMap<>();

    public ModelStatusService(RedisPersistenceService redisPersistence,
                              @Lazy RoutingService routingService,
                              CircuitBreakerService circuitBreakerService,
                              ModelRegistry modelRegistry,
                              ObjectMapper objectMapper) {
        this.redisPersistence = redisPersistence;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.modelRegistry = modelRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Initialize status for all models from Redis.
     */
    public void initializeAllModels() {
        for (String modelId : modelRegistry.getAllModelIds()) {
            initializeModel(modelId);
        }
    }

    /**
     * Initialize a single model's status from Redis.
     */
    public void initializeModel(String modelId) {
        List<HealthCheckResult> history = redisPersistence.getHealthCheckHistory(modelId);
        long usage = redisPersistence.getUsage(modelId);
        boolean circuitOpen = redisPersistence.isCircuitOpen(modelId);
        double emaLatency = redisPersistence.getEmaLatency(modelId, 0.0);
        int consecutiveErrors = redisPersistence.getConsecutiveErrors(modelId);

        // Determine initial status from history
        boolean isUp = false;
        long latency = 0;
        Instant lastChecked = Instant.now();
        String errorMessage = "Not yet checked";

        if (!history.isEmpty()) {
            HealthCheckResult lastPing = history.get(history.size() - 1);
            isUp = lastPing.isUp();
            latency = lastPing.getLatencyMs();
            lastChecked = lastPing.getTimestamp();
            if (!isUp) {
                errorMessage = lastPing.getErrorMessage();
            }
        }

        Model model = modelRegistry.getModel(modelId).orElse(null);
        List<String> categories = model != null 
                ? List.of(model.getPipeline().name()) 
                : List.of("Unknown");

        ModelStatus status = new ModelStatus(
                modelId,
                categories,
                isUp,
                latency,
                lastChecked,
                errorMessage,
                history,
                usage,
                routingService.getActiveConnections(modelId),
                routingService.getTps(modelId),
                circuitOpen
        );

        statusCache.put(modelId, status);
    }

    @Override
    public void updateStatus(String modelId, HealthCheckResult result) {
        ModelStatus existing = statusCache.get(modelId);
        if (existing == null) {
            initializeModel(modelId);
            existing = statusCache.get(modelId);
        }

        // Update with new health check result
        List<HealthCheckResult> updatedHistory = new ArrayList<>(existing.history());
        updatedHistory.add(result);
        
        // Keep history size manageable (same as Redis)
        if (updatedHistory.size() > 1440) {
            updatedHistory = updatedHistory.subList(updatedHistory.size() - 1440, updatedHistory.size());
        }

        ModelStatus updated = new ModelStatus(
                modelId,
                existing.categories(),
                result.isUp(),
                result.getLatencyMs(),
                result.getTimestamp(),
                result.isUp() ? null : result.getErrorMessage(),
                updatedHistory,
                redisPersistence.getUsage(modelId),
                routingService.getActiveConnections(modelId),
                routingService.getTps(modelId),
                circuitBreakerService.isCircuitOpen(modelId)
        );

        statusCache.put(modelId, updated);
    }

    @Override
    public boolean isModelUp(String modelId) {
        ModelStatus status = statusCache.get(modelId);
        return status != null && status.isUp();
    }

    @Override
    public double getTps(String modelId) {
        ModelStatus status = statusCache.get(modelId);
        return status != null ? status.tps() : 0.0;
    }

    @Override
    public long getLatency(String modelId) {
        ModelStatus status = statusCache.get(modelId);
        return status != null ? status.latencyMs() : 0L;
    }

    @Override
    public Instant getLastChecked(String modelId) {
        ModelStatus status = statusCache.get(modelId);
        return status != null ? status.lastChecked() : Instant.now();
    }

    @Override
    public String getErrorMessage(String modelId) {
        ModelStatus status = statusCache.get(modelId);
        return status != null ? status.errorMessage() : null;
    }

    /**
     * Get status for a single model.
     */
    public ModelStatus getStatus(String modelId) {
        return statusCache.get(modelId);
    }

    /**
     * Get all model statuses.
     */
    public List<ModelStatus> getAllStatuses() {
        return new ArrayList<>(statusCache.values());
    }

    /**
     * Get statuses filtered by pipeline.
     */
    public List<ModelStatus> getStatusesByPipeline(Pipeline pipeline) {
        return statusCache.values().stream()
                .filter(s -> s.categories().contains(pipeline.name()))
                .collect(Collectors.toList());
    }

    /**
     * Increment usage counter for a model.
     */
    public void incrementUsage(String modelId) {
        redisPersistence.incrementUsage(modelId);
        ModelStatus existing = statusCache.get(modelId);
        if (existing != null) {
            ModelStatus updated = new ModelStatus(
                    existing.model(),
                    existing.categories(),
                    existing.isUp(),
                    existing.latencyMs(),
                    existing.lastChecked(),
                    existing.errorMessage(),
                    existing.history(),
                    existing.totalUses() + 1,
                    existing.activeConnections(),
                    existing.tps(),
                    existing.circuitOpen()
            );
            statusCache.put(modelId, updated);
        }
    }
}