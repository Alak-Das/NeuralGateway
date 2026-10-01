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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ProviderAvailabilityService providerAvailabilityService;
    private final ModelRecoveryTracker modelRecoveryTracker;

    // Thread pool for parallel health checks to avoid blocking the scheduler thread
    private final ExecutorService healthCheckExecutor;
    private final AtomicBoolean healthCheckSweepInProgress = new AtomicBoolean(false);
    private final AtomicBoolean recoverySweepInProgress = new AtomicBoolean(false);
    private final Map<String, Instant> nextProviderProbeAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> nextRecoveryProviderProbeAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> providerFailureCounts = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              LlmProviderClient llmProviderClient,
                              RoutingService routingService, CircuitBreakerService circuitBreakerService,
                              ModelStatusUpdater modelStatusUpdater,
                              ProviderAvailabilityService providerAvailabilityService,
                              ModelRecoveryTracker modelRecoveryTracker) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.llmProviderClient = llmProviderClient;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.modelStatusUpdater = modelStatusUpdater;
        this.providerAvailabilityService = providerAvailabilityService;
        this.modelRecoveryTracker = modelRecoveryTracker;
        this.healthCheckExecutor = Executors.newFixedThreadPool(properties.getThreadPoolSize() > 0 ? properties.getThreadPoolSize() : 10);
    }

    /**
     * Backward-compatible constructor for direct service instantiation.
     */
    public HealthCheckService(HealthCheckProperties properties, ModelRegistry modelRegistry,
                              LlmProviderClient llmProviderClient,
                              RoutingService routingService, CircuitBreakerService circuitBreakerService,
                              ModelStatusUpdater modelStatusUpdater) {
        this(properties, modelRegistry, llmProviderClient, routingService, circuitBreakerService,
                modelStatusUpdater, null, new ModelRecoveryTracker());
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
     * Independently probes a small, prioritized batch of unhealthy models whose
     * per-model recovery backoff has elapsed. Provider cooldowns always win.
     */
    @Scheduled(initialDelayString = "${llm.health-check.recovery-initial-delay-ms:15000}",
            fixedDelayString = "${llm.health-check.recovery-interval-ms:5000}")
    @SchedulerLock(name = "HealthCheckService_performRecoverySweep",
            lockAtLeastFor = "${llm.health-check.recovery-lock-at-least-for:1s}",
            lockAtMostFor = "${llm.health-check.recovery-lock-at-most-for:6m}")
    public void performRecoverySweep() {
        if (!properties.isEnabled() || !recoverySweepInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            List<Model> dueModels = getDueRecoveryModels(Instant.now());

            for (int i = 0; i < dueModels.size(); i++) {
                Model model = dueModels.get(i);
                if (providerAvailabilityService != null
                        && !providerAvailabilityService.isAvailable(model.getProviderId())) continue;
                long delayMs = i * properties.getMinPingGapMs();
                AtomicBoolean recoveryFailureRecorded = new AtomicBoolean(false);
                CompletableFuture<Void> probe = CompletableFuture.supplyAsync(
                                () -> performActualPing(model.getId(), recoveryFailureRecorded),
                                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, healthCheckExecutor))
                        .orTimeout(delayMs + properties.getPingTimeoutMs(), TimeUnit.MILLISECONDS)
                        .handle((result, error) -> {
                            if (error == null) return result;
                            if (recoveryFailureRecorded.compareAndSet(false, true)) {
                                modelRecoveryTracker.recordFailure(model.getId());
                            }
                            return new HealthCheckResult(model.getId(), false, properties.getPingTimeoutMs(),
                                    Instant.now(), "Timeout/Error: " + error.getMessage(), true);
                        })
                        .thenAccept(result -> updateModelStatusFromResult(model.getId(), result));
                probe.join();
            }
        } finally {
            recoverySweepInProgress.set(false);
        }
    }

    /**
     * Record transient model-level failures observed during routed requests.
     */
    public void recordRoutedFailure(String modelId, Throwable failure) {
        if (ModelRecoveryTracker.isTransientModelFailure(failure)) {
            modelRecoveryTracker.recordFailure(modelId);
        }
    }

    /**
     * Clear recovery backoff after a routed request succeeds.
     */
    public void recordRoutedSuccess(String modelId) {
        modelRecoveryTracker.recordSuccess(modelId);
    }

    List<Model> getDueRecoveryModels(Instant now) {
        List<Model> dueModels = modelRegistry.getAllModelIds().stream()
                .map(modelRegistry::getModel)
                .flatMap(java.util.Optional::stream)
                .filter(model -> {
                    var config = modelRegistry.getProviderConfig(model.getProviderId());
                    return config != null && config.isHealthCheckEnabled();
                })
                .filter(Model::isEnabled)
                .filter(model -> isRecoveryCandidate(model.getId(), now))
                .filter(model -> {
                    Instant nextProviderProbe = nextRecoveryProviderProbeAt.get(model.getProviderId());
                    return nextProviderProbe == null || !nextProviderProbe.isAfter(now);
                })
                .filter(model -> providerAvailabilityService == null
                        || providerAvailabilityService.isAvailable(model.getProviderId()))
                .sorted(recoveryCandidateComparator())
                .filter(new DistinctProviderFilter())
                .limit(Math.max(1, properties.getRecoveryMaxModelsPerSweep()))
                .collect(Collectors.toList());
        for (Model model : dueModels) {
            nextRecoveryProviderProbeAt.put(model.getProviderId(), now.plusMillis(
                    Math.max(properties.getRecoveryIntervalMs(), properties.getMinPingGapMs())));
        }
        return dueModels;
    }

    /**
     * A model is a recovery candidate if its per-model recovery backoff has elapsed,
     * or its circuit is OPEN without any active recovery backoff (e.g. a stale OPEN
     * circuit restored from Redis that this process has never probed). Models already
     * in recovery backoff stay gated by {@link ModelRecoveryTracker#isDue} so a
     * genuinely down model is not hammered on the fast recovery cadence.
     */
    private boolean isRecoveryCandidate(String modelId, Instant now) {
        if (modelRecoveryTracker.isDue(modelId, now)) return true;
        return isBlockedWithoutRecoveryState(modelId);
    }

    private boolean isBlockedWithoutRecoveryState(String modelId) {
        return circuitBreakerService.isCircuitOpen(modelId) && !modelRecoveryTracker.hasState(modelId);
    }

    /**
     * Recovery sweep ordering: circuit-blocked models without recovery state first
     * (fastest unblocking), then priority (descending), then stable id order.
     */
    private Comparator<Model> recoveryCandidateComparator() {
        return Comparator
                .comparing((Model model) -> !isBlockedWithoutRecoveryState(model.getId()))
                .thenComparing(Comparator.comparingInt((Model model) -> model.getPriority()).reversed())
                .thenComparing(Model::getId);
    }

    private static final class DistinctProviderFilter implements java.util.function.Predicate<Model> {
        private final java.util.Set<String> selectedProviders = new java.util.HashSet<>();

        @Override
        public boolean test(Model model) {
            return selectedProviders.add(model.getProviderId());
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
        return performActualPing(modelId, null);
    }

    private HealthCheckResult performActualPing(String modelId, AtomicBoolean recoveryFailureRecorded) {
        long startTime = System.currentTimeMillis();
        String providerId = modelRegistry.getModel(modelId).map(Model::getProviderId).orElse(null);
        if (providerId != null && providerAvailabilityService != null
                && !providerAvailabilityService.isAvailable(providerId)) {
            return new HealthCheckResult(modelId, false, 0, Instant.now(), "Provider is in cooldown", true);
        }

        boolean isUp = false;
        long latency = 0;
        String errorMessage = null;

        try {
            Map<String, Object> response = performPingCall(modelId);
            Object responseModel = response == null ? null : response.get("model");
            boolean allowQualified = providerId != null && modelRegistry.getProviderConfig(providerId) != null
                    && modelRegistry.getProviderConfig(providerId).isAllowQualifiedModelIds();
            if (!(responseModel instanceof String actualModel)
                    || !modelIdMatches(modelId, actualModel, allowQualified)) {
                throw new IllegalStateException("Ping response model mismatch: requested '" + modelId
                        + "', received '" + responseModel + "'");
            }
            latency = System.currentTimeMillis() - startTime;
            isUp = true;
            modelRecoveryTracker.recordSuccess(modelId);
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
            if (ModelRecoveryTracker.isProviderWideFailure(e)) {
                if (providerAvailabilityService != null) {
                    if (e instanceof ProviderFailureException failure) {
                        providerAvailabilityService.recordFailure(failure);
                    } else if (e instanceof LlmProviderClient.RateLimitException rateLimit) {
                        providerAvailabilityService.recordFailure(new ProviderFailureException(
                                rateLimit.getMessage(), rateLimit.getStatusCode(), rateLimit.getProviderId(),
                                ProviderFailureType.RATE_LIMIT, null));
                    }
                }
            } else if (ModelRecoveryTracker.isTransientModelFailure(e)) {
                if (recoveryFailureRecorded == null || recoveryFailureRecorded.compareAndSet(false, true)) {
                    modelRecoveryTracker.recordFailure(modelId);
                }
            }
            if (statusCode == 429 || statusCode == 503) {
                backoffProviderProbe(modelId);
            }
        } catch (Exception e) {
            latency = System.currentTimeMillis() - startTime;
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.warn("Health check ping failed for model '{}': {}", modelId, errorMessage);
            log.debug("Health check failure details for model '{}'", modelId, e);
            if (ModelRecoveryTracker.isTransientModelFailure(e)) {
                modelRecoveryTracker.recordFailure(modelId);
            }
        }

        return new HealthCheckResult(modelId, isUp, latency, Instant.now(), errorMessage, true);
    }

    /**
     * Perform the actual ping call to the LLM API.
     */
    private Map<String, Object> performPingCall(String modelId) {
        // Create a minimal request with max_tokens=1. Must be mutable for the client.
        Map<String, Object> pingRequest = new java.util.HashMap<>(Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", properties.getPingMaxTokens(),
                "stream", false
        ));

        return llmProviderClient.call(modelId, pingRequest);
    }

    /**
     * Whether the upstream-reported model ID matches the requested model ID.
     * By default this is an exact match. When the provider opts in via
     * {@code allowQualifiedModelIds} (aggregators such as AntSeed echo
     * provider-qualified canonical IDs, e.g. "openai/gpt-oss-120b" for requested
     * "gpt-oss-120b"), the comparison additionally accepts:
     * <ul>
     *   <li>case-insensitive exact equality,</li>
     *   <li>a "/" + requested suffix (qualified ID with the same base name),</li>
     *   <li>a response that contains every dash/underscore/dot/slash-separated token
     *       of the requested ID (tolerates canonical/dated variants such as
     *       "Qwen/Qwen3-235B-A22B-Instruct-2507" for "qwen3-235b-instruct").</li>
     * </ul>
     * A null/missing reported ID always fails, preserving the "reject missing model"
     * safeguard.
     */
    private static boolean modelIdMatches(String requested, String actual, boolean allowQualified) {
        if (actual == null) {
            return false;
        }
        if (requested.equals(actual)) {
            return true;
        }
        if (!allowQualified) {
            return false;
        }
        String requestedLower = requested.toLowerCase(Locale.ROOT);
        String actualLower = actual.toLowerCase(Locale.ROOT);
        if (requestedLower.equals(actualLower)) {
            return true;
        }
        if (actualLower.endsWith("/" + requestedLower)) {
            return true;
        }
        for (String token : requestedLower.split("[-/._]")) {
            if (!token.isEmpty() && !actualLower.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Selects the models to probe in the next main sweep.
     * <p>
     * Instead of the old 1-model-per-provider round-robin (which needed N sweeps to
     * cover a provider's fleet, e.g. 28 minutes for 7 NVIDIA models at 4-minute
     * sweeps), every enabled model of a provider whose probe gate has elapsed is
     * probed in one sweep. Circuit-blocked models are probed first so a stale OPEN
     * circuit (e.g. restored from Redis) is closed by a verified probe as fast as
     * possible; the remaining models are ordered by EMA latency (fastest first) and
     * priority. Provider-level pacing/backoff ({@link #nextProviderProbeAt}) still
     * bounds how often a provider is contacted, and {@code maxModelsPerSweep} caps
     * how many of its models run per sweep so a large fleet cannot stall the sweep.
     */
    private List<Model> getPrioritizedModels() {
        List<Model> allModels = new ArrayList<>();
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.CODING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.REASONING));
        allModels.addAll(modelRegistry.getModelsByPipeline(Pipeline.VISION));

        // Disabled models stay visible in the dashboard but are never probed.
        allModels.removeIf(model -> !model.isEnabled());

        return selectModelsToProbe(allModels.stream().distinct().collect(Collectors.toList()), Instant.now());
    }

    /**
     * Core sweep selection: group the candidate models by provider, skip providers
     * that are paced/backed off or unavailable, and pick every model of each due
     * provider (blocked first, then EMA/priority, capped per provider).
     * Package-private for direct unit testing of the selection algorithm.
     */
    List<Model> selectModelsToProbe(List<Model> candidates, Instant now) {
        Map<String, List<Model>> modelsByProvider = candidates.stream()
                .collect(Collectors.groupingBy(Model::getProviderId));
        List<Model> selectedModels = new ArrayList<>();
        for (Map.Entry<String, List<Model>> provider : modelsByProvider.entrySet()) {
            String providerId = provider.getKey();
            var config = modelRegistry.getProviderConfig(providerId);
            if (config == null || !config.isHealthCheckEnabled()) continue;
            if (providerAvailabilityService != null && !providerAvailabilityService.isAvailable(providerId)) continue;

            // Provider-level pacing/backoff: skip providers just probed or in backoff.
            Instant nextProbe = nextProviderProbeAt.get(providerId);
            if (nextProbe != null && nextProbe.isAfter(now)) continue;

            List<Model> due = provider.getValue().stream()
                    .sorted(providerProbeComparator())
                    .limit(Math.max(1, properties.getMaxModelsPerSweep()))
                    .collect(Collectors.toList());
            selectedModels.addAll(due);
            nextProviderProbeAt.put(providerId, now.plusMillis(Math.max(60_000, config.getHealthCheckIntervalMs())));
        }

        // Order across providers so blocked models are paced first (the returned
        // order is what the minPingGapMs staggering applies to).
        return selectedModels.stream()
                .sorted(providerProbeComparator())
                .collect(Collectors.toList());
    }

    /**
     * Ordering for main-sweep probing: circuit-blocked models first (their stale
     * OPEN circuit blocks routing until a verified probe closes it), then EMA
     * latency (fastest first) when enabled, then priority, then stable id order.
     */
    private Comparator<Model> providerProbeComparator() {
        Comparator<Model> cmp = Comparator.comparing((Model model) -> !isCircuitBlocked(model.getId()));
        if (properties.isPrioritizeByEma()) {
            cmp = cmp.thenComparingDouble(model -> routingService.getEmaLatency(model.getId()));
        }
        return cmp.thenComparingInt(model -> -model.getPriority())
                .thenComparing(Model::getId);
    }

    private boolean isCircuitBlocked(String modelId) {
        return circuitBreakerService.isCircuitOpen(modelId);
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
            modelRecoveryTracker.recordSuccess(modelId);
            String providerId = modelRegistry.getModel(modelId).map(Model::getProviderId).orElse("");
            providerFailureCounts.remove(providerId);
            if (providerAvailabilityService != null && !providerId.isEmpty()) {
                providerAvailabilityService.recordSuccess(providerId);
            }
            routingService.updateEmaLatency(modelId, result.getLatencyMs());
            circuitBreakerService.recordSuccess(modelId);
            // A successful probe is authoritative evidence of recovery: close a stale
            // OPEN circuit (e.g. restored from Redis) so routing and the dashboard
            // unblock immediately instead of waiting for the passive half-open timeout.
            circuitBreakerService.markHealthy(modelId);
        } else {
            String providerId = modelRegistry.getModel(modelId).map(Model::getProviderId).orElse(null);
            boolean providerUnavailable = providerId != null && providerAvailabilityService != null
                    && !providerAvailabilityService.isAvailable(providerId);
            if (providerUnavailable) return;
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
