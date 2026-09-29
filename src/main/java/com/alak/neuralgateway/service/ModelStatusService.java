package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.event.ModelStatusChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service managing model status - implements both provider and updater interfaces.
 * Aggregates health data, telemetry, and Redis-persisted state into ModelStatus DTOs.
 */
@Service
public class ModelStatusService implements ModelStatusProvider, ModelStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(ModelStatusService.class);

    private final RedisPersistenceService redisPersistence;
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // In-memory status cache for fast reads
    private final Map<String, ModelStatus> statusCache = new ConcurrentHashMap<>();

    public ModelStatusService(RedisPersistenceService redisPersistence,
                              @Lazy RoutingService routingService,
                              CircuitBreakerService circuitBreakerService,
                              ModelRegistry modelRegistry,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.redisPersistence = redisPersistence;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.modelRegistry = modelRegistry;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Initialize status for all models from Redis.
     */
    @PostConstruct
    public void initializeAllModels() {
        for (String modelId : modelRegistry.getAllModelIds()) {
            initializeModel(modelId);
        }
    }

    /**
     * Initialize a single model's status from Redis.
     */
    public void initializeModel(String modelId) {
        circuitBreakerService.initializeModel(modelId);
        List<HealthCheckResult> history = redisPersistence.getHealthCheckHistory(modelId);
        long usage = redisPersistence.getUsage(modelId);
        boolean circuitOpen = redisPersistence.isCircuitOpen(modelId);
        double emaLatency = redisPersistence.getEmaLatency(modelId, 0.0);
        int consecutiveErrors = redisPersistence.getConsecutiveErrors(modelId);

        // Determine initial status from history
        boolean isUp = false;
        long latency = 0;
        Instant lastChecked = null;
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
                ? model.getPipelines().stream().map(Enum::name).collect(java.util.stream.Collectors.toList()) 
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
                0, // activeConnections is 0 on startup
                0.0, // tps is 0.0 on startup
                circuitOpen,
                model != null ? model.getProviderId() : "unknown",
                model != null ? model.getPriority() : 0
        );

        statusCache.put(modelId, status);
        
        // Publish event to trigger SSE broadcast
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, status));
        }
    }

    @Override
    public void updateStatus(String modelId, HealthCheckResult result) {
        log.debug("Persisting model status history: modelId={}, up={}, latencyMs={}",
                modelId, result.isUp(), result.getLatencyMs());
        redisPersistence.saveHealthCheckResult(modelId, result);

        ModelStatus updated = statusCache.compute(modelId, (k, existing) -> {
            if (existing == null) {
                initializeModel(modelId);
                existing = statusCache.get(modelId);
            }

            List<HealthCheckResult> updatedHistory = (existing != null && existing.history() != null)
                    ? new ArrayList<>(existing.history())
                    : new ArrayList<>();
            updatedHistory.add(result);

            if (updatedHistory.size() > 1440) {
                updatedHistory = updatedHistory.subList(updatedHistory.size() - 1440, updatedHistory.size());
            }

            List<String> categories = existing != null ? existing.categories() : List.of("Unknown");

            return new ModelStatus(
                    modelId,
                    categories,
                    result.isUp(),
                    result.getLatencyMs(),
                    result.getTimestamp(),
                    result.isUp() ? null : result.getErrorMessage(),
                    updatedHistory,
                    redisPersistence.getUsage(modelId),
                    routingService.getActiveConnections(modelId),
                    routingService.getTps(modelId),
                    circuitBreakerService.isCircuitOpen(modelId),
                    existing != null ? existing.provider() : "unknown",
                    existing != null ? existing.priority() : 0
            );
        });

        // Publish event to trigger SSE broadcast instantly
        if (eventPublisher != null && updated != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, updated));
        }
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
     * Apply a status snapshot published by another gateway instance.
     */
    public void updateFromRemote(ModelStatus status) {
        if (status != null && status.model() != null) {
            circuitBreakerService.initializeModel(status.model());
            statusCache.put(status.model(), status);
        }
    }

    /**
     * Get all model statuses.
     */
    public List<ModelStatus> getAllStatuses() {
        return statusCache.values().stream()
                .map(status -> new ModelStatus(
                        status.model(),
                        status.categories(),
                        status.isUp(),
                        status.latencyMs(),
                        status.lastChecked(),
                        status.errorMessage(),
                        status.history(),
                        redisPersistence.getUsage(status.model()),
                        routingService.getActiveConnections(status.model()),
                        status.tps(),
                        status.circuitOpen(),
                        status.provider(),
                        status.priority()
                ))
                .collect(Collectors.toList());
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
        ModelStatus updated = statusCache.computeIfPresent(modelId, (k, existing) -> new ModelStatus(
                existing.model(),
                existing.categories(),
                existing.isUp(),
                existing.latencyMs(),
                existing.lastChecked(),
                existing.errorMessage(),
                existing.history(),
                redisPersistence.getUsage(modelId),
                routingService.getActiveConnections(modelId),
                existing.tps(),
                existing.circuitOpen(),
                existing.provider(),
                existing.priority()
        ));

        if (updated != null && eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, updated));
        }
    }

    /**
     * Updates the TPS for a model.
     */
    public void updateTps(String modelId, double tps) {
        redisPersistence.saveTps(modelId, tps);
        ModelStatus updated = statusCache.computeIfPresent(modelId, (k, existing) -> new ModelStatus(
                existing.model(),
                existing.categories(),
                existing.isUp(),
                existing.latencyMs(),
                existing.lastChecked(),
                existing.errorMessage(),
                existing.history(),
                existing.totalUses(),
                routingService.getActiveConnections(modelId),
                tps,
                existing.circuitOpen(),
                existing.provider(),
                existing.priority()
        ));
        
        if (updated != null && eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, updated));
        }
    }
}
