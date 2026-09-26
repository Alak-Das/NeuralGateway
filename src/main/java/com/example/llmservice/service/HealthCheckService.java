package com.example.llmservice.service;

import com.example.llmservice.config.HealthCheckProperties;
import com.example.llmservice.domain.health.HealthCheckResult;
import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service responsible for periodic health checks of all models.
 * Performs sequential pings with rate pacing to avoid burst limits.
 */
@Service
public class HealthCheckService {

    private final HealthCheckProperties properties;
    private final ModelRegistry modelRegistry;
    private final NvidiaLlmClient nvidiaLlmClient;
    private final RedisPersistenceService redisPersistence;
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final ModelStatusUpdater modelStatusUpdater;

    // Rate limiting for sequential pings
    private final Object pingLock = new Object();
    private final AtomicLong lastPingEndTime = new AtomicLong(0);

    // Model prioritization cache
    private final Map<String, Double> modelPriorityCache = new ConcurrentHashMap<>();

    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              NvidiaLlmClient nvidiaLlmClient, RedisPersistenceService redisPersistence,
                              RoutingService routingService, CircuitBreakerService circuitBreakerService,
                              ModelStatusUpdater modelStatusUpdater) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.nvidiaLlmClient = nvidiaLlmClient;
        this.redisPersistence = redisPersistence;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.modelStatusUpdater = modelStatusUpdater;
    }

    /**
     * Scheduled health check sweep - runs at configured interval.
     */
    @Scheduled(fixedDelayString = "${llm.health-check.intervalMs:300000}")
    public void performHealthCheckSweep() {
        if (!properties.isEnabled()) {
            return;
        }

        List<Model> modelsToPing = getPrioritizedModels();
        
        for (Model model : modelsToPing) {
            try {
                HealthCheckResult result = pingSingleModel(model.getId());
                updateModelStatusFromResult(model.getId(), result);
            } catch (Exception e) {
                // Log but continue with other models
            }
        }
    }

    /**
     * Perform a manual health check for a single model.
     */
    public HealthCheckResult pingModel(String modelId) {
        return pingSingleModel(modelId);
    }

    /**
     * Ping a single model with rate pacing.
     */
    private HealthCheckResult pingSingleModel(String modelId) {
        // Rate pacing - ensure minimum gap between pings
        synchronized (pingLock) {
            long timeSinceLastPing = System.currentTimeMillis() - lastPingEndTime.get();
            if (lastPingEndTime.get() > 0 && timeSinceLastPing < properties.getMinPingGapMs()) {
                try {
                    Thread.sleep(properties.getMinPingGapMs() - timeSinceLastPing);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            long startTime = System.currentTimeMillis();
            boolean isUp = false;
            long latency = 0;
            String errorMessage = null;

            try {
                performPingCall(modelId);
                latency = System.currentTimeMillis() - startTime;
                isUp = true;
            } catch (Exception e) {
                latency = System.currentTimeMillis() - startTime;
                errorMessage = e.getMessage();
            } finally {
                lastPingEndTime.set(System.currentTimeMillis());
            }

            return new HealthCheckResult(modelId, isUp, latency, Instant.now(), errorMessage);
        }
    }

    /**
     * Perform the actual ping call to the NVIDIA API.
     */
    private void performPingCall(String modelId) {
        // Create a minimal request with max_tokens=1
        Map<String, Object> pingRequest = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", properties.getPingMaxTokens(),
                "stream", false
        );
        
        nvidiaLlmClient.call(modelId, pingRequest);
    }

    /**
     * Get all models prioritized by EMA latency (fastest first) if enabled.
     */
    private List<Model> getPrioritizedModels() {
        List<Model> allModels = new ArrayList<>();
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.CODING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.REASONING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.VISION));

        if (properties.isPrioritizeByEma()) {
            return allModels.stream()
                    .sorted(Comparator.comparingDouble(m -> routingService.getEmaLatency(m.getId())))
                    .collect(Collectors.toList());
        }

        return allModels;
    }

    /**
     * Update model status from health check result.
     */
    private void updateModelStatusFromResult(String modelId, HealthCheckResult result) {
        // Update routing telemetry
        if (result.isUp()) {
            routingService.updateEmaLatency(modelId, result.getLatencyMs());
            circuitBreakerService.recordSuccess(modelId);
        } else {
            circuitBreakerService.recordFailure(modelId, new RuntimeException(result.getErrorMessage()));
        }

        // Persist to Redis
        redisPersistence.saveHealthCheckResult(modelId, result);

        // Update in-memory status
        modelStatusUpdater.updateStatus(modelId, result);
    }
}