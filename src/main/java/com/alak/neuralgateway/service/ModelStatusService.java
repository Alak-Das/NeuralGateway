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
        private final ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // In-memory status cache for fast reads
    private final Map<String, ModelStatus> statusCache = new ConcurrentHashMap<>();

    public ModelStatusService(RedisPersistenceService redisPersistence,
                              @Lazy RoutingService routingService,
                              ModelRegistry modelRegistry,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.redisPersistence = redisPersistence;
        this.routingService = routingService;
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

    public ModelStatus buildInitialModelStatus(String modelId) {
        List<HealthCheckResult> history = redisPersistence.getHealthCheckHistory(modelId);
        long usage = redisPersistence.getUsage(modelId);
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
        boolean circuitOpen = !isUp || consecutiveErrors >= 3;

        Model model = modelRegistry.getModel(modelId).orElse(null);
        List<String> categories = model != null 
                ? model.getPipelines().stream().map(Enum::name).collect(java.util.stream.Collectors.toList()) 
                : List.of("Unknown");

        return new ModelStatus(
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
                model != null ? model.getPriority() : 0,
                model != null && model.isEnabled()
        );
    }

    /**
     * Initialize a single model's status from Redis.
     */
    public void initializeModel(String modelId) {
        ModelStatus status = buildInitialModelStatus(modelId);
        statusCache.put(modelId, status);
        
        // Publish event to trigger SSE broadcast
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, status));
        }
    }

    @Override
    public void updateStatus(String modelId, HealthCheckResult result) {
        log.debug("Persisting model status history: modelId={}, up={}, latencyMs={}, probe={}",
                modelId, result.isUp(), result.getLatencyMs(), result.isBackgroundProbe());

        boolean effectiveIsUp;
        boolean effectiveCircuitOpen;
        String effectiveErrorMessage;

        if (result.isUp()) {
            redisPersistence.resetConsecutiveErrors(modelId);
            effectiveIsUp = true;
            effectiveCircuitOpen = false;
            effectiveErrorMessage = null;
        } else {
            if (result.isBackgroundProbe()) {
                // Background synthetic probe failed: genuine health check failure
                effectiveIsUp = false;
                effectiveCircuitOpen = true;
                effectiveErrorMessage = result.getErrorMessage();
            } else {
                // Real request error: apply 3-consecutive-errors anti-flapping threshold
                int consecutive = redisPersistence.incrementConsecutiveErrors(modelId);
                if (consecutive >= 3) {
                    effectiveIsUp = false;
                    effectiveCircuitOpen = true;
                    effectiveErrorMessage = result.getErrorMessage();
                    log.warn("Model '{}' marked DOWN after {} consecutive request errors: {}",
                            modelId, consecutive, result.getErrorMessage());
                } else {
                    ModelStatus existing = statusCache.get(modelId);
                    effectiveIsUp = existing != null ? existing.isUp() : true;
                    effectiveCircuitOpen = existing != null ? existing.circuitOpen() : false;
                    effectiveErrorMessage = effectiveIsUp ? null : result.getErrorMessage();
                    log.info("Model '{}' recorded error ({}/3 consecutive errors) - remaining UP: {}",
                            modelId, consecutive, result.getErrorMessage());
                }
            }
        }

        HealthCheckResult savedResult = new HealthCheckResult(
                modelId,
                result.isUp(),
                result.getLatencyMs(),
                result.getTimestamp(),
                result.getErrorMessage(),
                result.isBackgroundProbe()
        );
        redisPersistence.saveHealthCheckResult(modelId, savedResult);

        ModelStatus updated = statusCache.compute(modelId, (k, existing) -> {
            if (existing == null) {
                existing = buildInitialModelStatus(modelId);
            }

            List<HealthCheckResult> updatedHistory = (existing != null && existing.history() != null)
                    ? new ArrayList<>(existing.history())
                    : new ArrayList<>();
            updatedHistory.add(savedResult);

            if (updatedHistory.size() > 1440) {
                updatedHistory = updatedHistory.subList(updatedHistory.size() - 1440, updatedHistory.size());
            }

            List<String> categories = existing != null ? existing.categories() : List.of("Unknown");

            return new ModelStatus(
                    modelId,
                    categories,
                    effectiveIsUp,
                    result.getLatencyMs(),
                    result.getTimestamp(),
                    effectiveErrorMessage,
                    updatedHistory,
                    redisPersistence.getUsage(modelId),
                    routingService.getActiveConnections(modelId),
                    routingService.getTps(modelId),
                    effectiveCircuitOpen,
                    existing != null ? existing.provider() : "unknown",
                    modelRegistry.getModel(modelId).map(com.alak.neuralgateway.domain.model.Model::getPriority).orElse(existing != null ? existing.priority() : 0),
                    modelRegistry.getModel(modelId).map(com.alak.neuralgateway.domain.model.Model::isEnabled).orElse(existing != null ? existing.enabled() : true)
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
        if (status == null || !status.isUp()) {
            return false;
        }
        if (status.provider() != null && !status.provider().isBlank()) {
            if (redisPersistence.getProviderUnavailableReason(status.provider()) != null) {
                return false;
            }
        }
        return true;
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
        ModelStatus status = getStatus(modelId);
        return status != null ? status.errorMessage() : null;
    }

    /**
     * Get status for a single model.
     */
    public ModelStatus getStatus(String modelId) {
        return enrichStatus(statusCache.get(modelId));
    }

    /**
     * Apply a status snapshot published by another gateway instance.
     */
    public void updateFromRemote(ModelStatus status) {
        if (status != null && status.model() != null) {
            statusCache.put(status.model(), status);
        }
    }

    /**
     * Get all model statuses.
     */
    public List<ModelStatus> getAllStatuses() {
        return statusCache.values().stream()
                .map(this::enrichStatus)
                .collect(Collectors.toList());
    }

    /**
     * Get statuses filtered by pipeline.
     */
    public List<ModelStatus> getStatusesByPipeline(Pipeline pipeline) {
        return statusCache.values().stream()
                .filter(s -> s.categories().contains(pipeline.name()))
                .map(this::enrichStatus)
                .collect(Collectors.toList());
    }

    private ModelStatus enrichStatus(ModelStatus status) {
        if (status == null) return null;
        String providerCooldown = status.provider() != null && !status.provider().isBlank()
                ? redisPersistence.getProviderUnavailableReason(status.provider())
                : null;
        boolean circuitOpen = status.circuitOpen() || providerCooldown != null;
        String errorMessage = status.errorMessage();
        if ((errorMessage == null || errorMessage.isBlank()) && providerCooldown != null) {
            errorMessage = "Provider cooldown: " + providerCooldown;
        }
        return new ModelStatus(
                status.model(),
                status.categories(),
                status.isUp(),
                status.latencyMs(),
                status.lastChecked(),
                errorMessage,
                status.history(),
                redisPersistence.getUsage(status.model()),
                routingService.getActiveConnections(status.model()),
                status.tps(),
                circuitOpen,
                status.provider(),
                status.priority(),
                status.enabled()
        );
    }

    /**
     * Resets the circuit breaker and error state for a model and its provider models in the cache.
     */
    public void resetCircuitBreaker(String modelId) {
        if (modelId == null || modelId.isBlank()) return;
        redisPersistence.resetConsecutiveErrors(modelId);
        redisPersistence.clearModelRecoveryBackoff(modelId);

        List<String> targetModels = new ArrayList<>();
        if (statusCache.containsKey(modelId)) {
            targetModels.add(modelId);
        } else {
            for (ModelStatus ms : statusCache.values()) {
                if (modelId.equalsIgnoreCase(ms.provider())) {
                    targetModels.add(ms.model());
                }
            }
        }

        for (String target : targetModels) {
            redisPersistence.resetConsecutiveErrors(target);
            redisPersistence.clearModelRecoveryBackoff(target);
            ModelStatus existing = statusCache.get(target);
            if (existing != null) {
                ModelStatus resetStatus = new ModelStatus(
                        existing.model(),
                        existing.categories(),
                        true,
                        existing.latencyMs(),
                        Instant.now(),
                        null,
                        existing.history(),
                        existing.totalUses(),
                        existing.activeConnections(),
                        existing.tps(),
                        false,
                        existing.provider(),
                        existing.priority(),
                        existing.enabled()
                );
                statusCache.put(target, resetStatus);
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new ModelStatusChangedEvent(this, resetStatus));
                }
            }
        }
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
                existing.priority(),
                existing.enabled()
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
                existing.priority(),
                existing.enabled()
        ));
        
        if (updated != null && eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, updated));
        }
    }

    /**
     * Updates in-memory model status cache when dynamic model configuration changes,
     * and publishes an SSE event so the UI updates immediately.
     */
    public void notifyModelConfigUpdated(String modelId) {
        Model model = modelRegistry.getModel(modelId).orElse(null);
        if (model == null) return;

        List<String> categories = model.getPipelines() != null
                ? model.getPipelines().stream().map(Enum::name).collect(Collectors.toList())
                : List.of("Unknown");

        ModelStatus updated = statusCache.compute(modelId, (k, existing) -> {
            if (existing == null) {
                return buildInitialModelStatus(modelId);
            }
            return new ModelStatus(
                    existing.model(),
                    categories,
                    existing.isUp(),
                    existing.latencyMs(),
                    existing.lastChecked(),
                    existing.errorMessage(),
                    existing.history(),
                    existing.totalUses(),
                    existing.activeConnections(),
                    existing.tps(),
                    existing.circuitOpen(),
                    model.getProviderId() != null ? model.getProviderId() : existing.provider(),
                    model.getPriority(),
                    model.isEnabled()
            );
        });

        if (updated != null && eventPublisher != null) {
            eventPublisher.publishEvent(new ModelStatusChangedEvent(this, updated));
        }
    }
}
