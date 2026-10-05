package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.HealthCheckProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Service responsible for background health checks of all models.
 * Spawns a lightweight Java 21 Virtual Thread per model for continuous polling.
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);
    private static final long HEALTH_CHECK_INTERVAL_MS = 60_000;
    private static final long STAGGER_DELAY_MS = 15_000;

    private final HealthCheckProperties properties;
    private final ModelRegistry modelRegistry;
    private final LlmProviderClient llmProviderClient;
    private final ModelStatusUpdater modelStatusUpdater;
    
    // Utilize Java 21 Virtual Threads
    private final ExecutorService healthCheckExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = true;

    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              LlmProviderClient llmProviderClient, ModelStatusUpdater modelStatusUpdater) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.llmProviderClient = llmProviderClient;
        this.modelStatusUpdater = modelStatusUpdater;
    }

    @PostConstruct
    public void startHealthChecks() {
        if (!properties.isEnabled()) {
            log.info("Health checks are disabled");
            return;
        }

        List<Model> models = getAllEnabledModelsSortedByPriority();
        log.info("Starting virtual threads for {} models (stagger: {}ms, interval: {}ms)",
                models.size(), STAGGER_DELAY_MS, HEALTH_CHECK_INTERVAL_MS);

        for (int i = 0; i < models.size(); i++) {
            final Model model = models.get(i);
            final long initialDelay = (long) i * STAGGER_DELAY_MS;

            healthCheckExecutor.submit(() -> {
                // Set MDC context for this health check thread
                String healthCheckTxId = "health-" + model.getId().replace("/", "-").replace(".", "-");
                MDC.put("txId", healthCheckTxId);
                MDC.put("requester", "HealthCheckService");
                Thread.currentThread().setName("health-check-" + model.getId());
                try { 
                    Thread.sleep(initialDelay); 
                } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt(); 
                    return; 
                }
                
                 log.info("Background health check loop started for model: {}", model.getId());

                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        try {
                            HealthCheckResult result = performActualPing(model.getId());
                            modelStatusUpdater.updateStatus(model.getId(), result);
                            log.debug("Health check for '{}': {} ({}ms)", model.getId(), result.isUp() ? "UP" : "DOWN", result.getLatencyMs());
                        } catch (Exception e) {
                            log.error("Unexpected error in health check thread for '{}'", model.getId(), e);
                        }
                        try {
                            Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    MDC.clear();
                }
            });
        }
    }

    private List<Model> getAllEnabledModelsSortedByPriority() {
        return modelRegistry.getAllModelIds().stream()
                .map(modelRegistry::getModel)
                .flatMap(Optional::stream)
                .filter(Model::isEnabled)
                .distinct()
                .sorted(Comparator.comparingInt((Model m) -> m.getPriority()).reversed().thenComparing(Model::getId))
                .collect(Collectors.toList());
    }

    public HealthCheckResult pingModel(String modelId) {
        return performActualPing(modelId);
    }

    private HealthCheckResult performActualPing(String modelId) {
        long startTime = System.currentTimeMillis();
        boolean isUp = false;
        String errorMessage = null;

        try {
            Map<String, Object> response = performPingCall(modelId);
            Object responseModel = response == null ? null : response.get("model");
            String providerId = modelRegistry.getModel(modelId).map(Model::getProviderId).orElse(null);
            boolean allowQualified = providerId != null && modelRegistry.getProviderConfig(providerId) != null
                    && modelRegistry.getProviderConfig(providerId).isAllowQualifiedModelIds();
            
            if (!(responseModel instanceof String actualModel) || !modelIdMatches(modelId, actualModel, allowQualified)) {
                throw new IllegalStateException("Ping response model mismatch: requested '" + modelId + "', received '" + responseModel + "'");
            }
            isUp = true;
        } catch (LlmProviderClient.UpstreamServiceException e) {
            int statusCode = e.getStatusCode();
            if (statusCode == 401 || statusCode == 403) {
                errorMessage = String.format("Upstream provider error (%d): %s", statusCode, e.getMessage());
            } else {
                errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        } catch (Exception e) {
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        }

        long latency = System.currentTimeMillis() - startTime;
        return new HealthCheckResult(modelId, isUp, latency, Instant.now(), errorMessage, true);
    }

    private Map<String, Object> performPingCall(String modelId) {
        Map<String, Object> pingRequest = new HashMap<>(Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", properties.getPingMaxTokens(),
                "stream", false
        ));
        return llmProviderClient.call(modelId, pingRequest);
    }

    private static boolean modelIdMatches(String requested, String actual, boolean allowQualified) {
        if (actual == null) {
            return false;
        }
        if (allowQualified) {
            // For providers that allow qualified model IDs (like AntSeed), accept any returned model name.
            return true;
        }
        return requested.equals(actual);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        healthCheckExecutor.shutdownNow();
    }
}
