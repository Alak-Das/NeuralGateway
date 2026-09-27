package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.HealthCheckProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Service responsible for periodic health checks of all models.
 * Performs fully asynchronous, parallel pings to ensure timely status updates
 * and avoid head-of-line blocking if a model hangs.
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final HealthCheckProperties properties;
    private final ModelRegistry modelRegistry;
    private final LlmProviderClient llmProviderClient;
    private final RedisPersistenceService redisPersistence;
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final ModelStatusUpdater modelStatusUpdater;

    // Thread pool for parallel health checks to avoid blocking the scheduler thread
    private final ExecutorService healthCheckExecutor;

    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              LlmProviderClient llmProviderClient, RedisPersistenceService redisPersistence,
                              RoutingService routingService, CircuitBreakerService circuitBreakerService,
                              ModelStatusUpdater modelStatusUpdater) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.llmProviderClient = llmProviderClient;
        this.redisPersistence = redisPersistence;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.modelStatusUpdater = modelStatusUpdater;
        this.healthCheckExecutor = Executors.newFixedThreadPool(properties.getThreadPoolSize() > 0 ? properties.getThreadPoolSize() : 10);
    }

    /**
     * Scheduled health check sweep - runs at configured interval.
     * Executes concurrently so one slow model doesn't block the rest.
     */
    @Scheduled(initialDelayString = "${llm.health-check.initialDelayMs:5000}", fixedDelayString = "${llm.health-check.intervalMs:240000}")
    @SchedulerLock(name = "HealthCheckService_performHealthCheckSweep", lockAtLeastFor = "${llm.health-check.lock-at-least-for:10s}", lockAtMostFor = "${llm.health-check.lock-at-most-for:4m}")
    public void performHealthCheckSweep() {
        if (!properties.isEnabled()) {
            return;
        }

        List<Model> modelsToPing = getPrioritizedModels();
        
        for (int i = 0; i < modelsToPing.size(); i++) {
            Model model = modelsToPing.get(i);
            long delayMs = i * 500L; // Stagger each ping by 500ms to pace requests
            
            CompletableFuture.runAsync(() -> {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {}
                }
            }, healthCheckExecutor)
            .thenCompose(v -> CompletableFuture.supplyAsync(() -> performActualPing(model.getId()), healthCheckExecutor))
            // Wait up to the configured timeout (e.g. 2 minutes)
            .orTimeout(properties.getPingTimeoutMs(), TimeUnit.MILLISECONDS)
            .handle((result, ex) -> {
                if (ex != null) {
                    return new HealthCheckResult(model.getId(), false, properties.getPingTimeoutMs(), Instant.now(), "Timeout/Error: " + ex.getMessage(), true);
                }
                return result;
            })
            .thenAccept(result -> updateModelStatusFromResult(model.getId(), result));
        }
    }

    /**
     * Perform a manual health check for a single model (synchronous for caller).
     */
    public HealthCheckResult pingModel(String modelId) {
        return performActualPing(modelId);
    }

    /**
     * Performs the actual ping logic without blocking synchronization.
     */
    private HealthCheckResult performActualPing(String modelId) {
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
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.warn("Health check ping failed for model '{}': {}", modelId, errorMessage);
            log.debug("Health check failure details for model '{}'", modelId, e);
        }

        return new HealthCheckResult(modelId, isUp, latency, Instant.now(), errorMessage, true);
    }

    /**
     * Perform the actual ping call to the LLM API.
     */
    private void performPingCall(String modelId) {
        // Create a minimal request with max_tokens=1. Must be mutable for the client.
        Map<String, Object> pingRequest = new java.util.HashMap<>(Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", properties.getPingMaxTokens(),
                "stream", false
        ));
        
        llmProviderClient.call(modelId, pingRequest);
    }

    /**
     * Get all models prioritized by EMA latency (fastest first) if enabled.
     * Deduplicates models configured across multiple pipelines to avoid redundant pings.
     */
    private List<Model> getPrioritizedModels() {
        List<Model> allModels = new ArrayList<>();
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.CODING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.REASONING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.VISION));

        List<Model> uniqueModels = allModels.stream()
                .distinct()
                .collect(Collectors.toList());

        if (properties.isPrioritizeByEma()) {
            return uniqueModels.stream()
                    .sorted(Comparator.comparingDouble(m -> routingService.getEmaLatency(m.getId())))
                    .collect(Collectors.toList());
        }

        return uniqueModels;
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

        // Update in-memory status (which now instantly fires SSE broadcast)
        modelStatusUpdater.updateStatus(modelId, result);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down HealthCheckService executor pool...");
        healthCheckExecutor.shutdown();
        try {
            if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                healthCheckExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthCheckExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
