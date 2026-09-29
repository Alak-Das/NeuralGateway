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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final ModelStatusUpdater modelStatusUpdater;

    // Thread pool for parallel health checks to avoid blocking the scheduler thread
    private final ExecutorService healthCheckExecutor;
    private final AtomicBoolean healthCheckSweepInProgress = new AtomicBoolean(false);
    private final Map<String, Instant> nextProviderProbeAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> providerFailureCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> providerProbeCursor = new ConcurrentHashMap<>();

    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              LlmProviderClient llmProviderClient,
                              RoutingService routingService, CircuitBreakerService circuitBreakerService,
                              ModelStatusUpdater modelStatusUpdater) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.llmProviderClient = llmProviderClient;
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
    @SchedulerLock(name = "HealthCheckService_performHealthCheckSweep", lockAtLeastFor = "${llm.health-check.lock-at-least-for:10s}", lockAtMostFor = "${llm.health-check.lock-at-most-for:5m}")
    public void performHealthCheckSweep() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!healthCheckSweepInProgress.compareAndSet(false, true)) {
            log.debug("Skipping health check sweep because the previous sweep is still running");
            return;
        }

        List<Model> modelsToPing = getPrioritizedModels();
        List<CompletableFuture<?>> healthChecks = new ArrayList<>();
        
        for (int i = 0; i < modelsToPing.size(); i++) {
            Model model = modelsToPing.get(i);
            long delayMs = i * properties.getMinPingGapMs();

            CompletableFuture<Void> healthCheck = CompletableFuture.supplyAsync(
                    () -> performActualPing(model.getId()),
                    CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, healthCheckExecutor))
            // The delay paces when the ping starts, so don't let its timeout expire before it runs.
            .orTimeout(delayMs + properties.getPingTimeoutMs(), TimeUnit.MILLISECONDS)
            .handle((result, ex) -> {
                if (ex != null) {
                    return new HealthCheckResult(model.getId(), false, properties.getPingTimeoutMs(), Instant.now(), "Timeout/Error: " + ex.getMessage(), true);
                }
                return result;
            })
            .thenAccept(result -> updateModelStatusFromResult(model.getId(), result));
            healthChecks.add(healthCheck);
        }

        try {
            // Keep ShedLock held until every paced probe has finished. Releasing it after
            // submission allowed another replica to start an overlapping sweep.
            CompletableFuture.allOf(healthChecks.toArray(CompletableFuture[]::new)).join();
        } finally {
            healthCheckSweepInProgress.set(false);
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
        } catch (LlmProviderClient.UpstreamServiceException e) {
            latency = System.currentTimeMillis() - startTime;
            // Preserve HTTP status code so the UI can recognize auth failures (401/403) distinctly.
            int statusCode = e.getStatusCode();
            if (statusCode == 401 || statusCode == 403) {
                errorMessage = String.format("Upstream provider error (%d): %s", statusCode, e.getMessage());
            } else {
                errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            log.warn("Health check ping failed for model '{}': {}", modelId, errorMessage);
            log.debug("Health check failure details for model '{}'", modelId, e);
            if (statusCode == 429 || statusCode == 503) {
                backoffProviderProbe(modelId);
            }
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

        Instant now = Instant.now();
        Map<String, List<Model>> modelsByProvider = allModels.stream()
                .distinct()
                .collect(Collectors.groupingBy(Model::getProviderId));
        List<Model> selectedModels = new ArrayList<>();
        for (Map.Entry<String, List<Model>> provider : modelsByProvider.entrySet()) {
            String providerId = provider.getKey();
            var config = modelRegistry.getProviderConfig(providerId);
            if (config == null || !config.isHealthCheckEnabled()) continue;
            Instant nextProbe = nextProviderProbeAt.get(providerId);
            if (nextProbe != null && nextProbe.isAfter(now)) continue;

            List<Model> providerModels = provider.getValue();
            int index = providerProbeCursor.computeIfAbsent(providerId, ignored -> new AtomicInteger())
                    .getAndUpdate(current -> (current + 1) % providerModels.size());
            selectedModels.add(providerModels.get(index));
            nextProviderProbeAt.put(providerId, now.plusMillis(Math.max(60_000, config.getHealthCheckIntervalMs())));
        }

        if (properties.isPrioritizeByEma()) {
            return selectedModels.stream()
                    .sorted(Comparator.comparingDouble(m -> routingService.getEmaLatency(m.getId())))
                    .collect(Collectors.toList());
        }

        return selectedModels;
    }

    private void backoffProviderProbe(String modelId) {
        modelRegistry.getModel(modelId).ifPresent(model -> {
            var config = modelRegistry.getProviderConfig(model.getProviderId());
            if (config == null) return;
            int failures = providerFailureCounts.merge(model.getProviderId(), 1, Integer::sum);
            long base = Math.max(60_000, config.getHealthCheckIntervalMs());
            long multiplier = 1L << Math.min(failures - 1, 10);
            long backoff = Math.min(config.getHealthCheckMaxBackoffMs(), base * multiplier);
            nextProviderProbeAt.put(model.getProviderId(), Instant.now().plusMillis(backoff));
            log.info("Backing off health probes for provider '{}' for {}ms after overload", model.getProviderId(), backoff);
        });
    }

    /**
     * Update model status from health check result.
     */
    private void updateModelStatusFromResult(String modelId, HealthCheckResult result) {
        // Update routing telemetry
        if (result.isUp()) {
            providerFailureCounts.remove(modelRegistry.getModel(modelId).map(Model::getProviderId).orElse(""));
            routingService.updateEmaLatency(modelId, result.getLatencyMs());
            circuitBreakerService.recordSuccess(modelId);
        } else {
            circuitBreakerService.recordFailure(modelId, new RuntimeException(result.getErrorMessage()));
        }

        // Update and persist status through the shared updater, which also fires SSE broadcasts.
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
