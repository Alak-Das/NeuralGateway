package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.DataRetentionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Tracks when a model made unhealthy by a transient routed failure may next be
 * probed. The delay doubles from 30 seconds and is capped at 120 seconds, with
 * bounded jitter to spread recovery probes across models and gateway replicas.
 */
@Component
public class ModelRecoveryTracker {
    static final long INITIAL_BACKOFF_MS = 30_000;
    static final long MAX_BACKOFF_MS = 120_000;
    private static final double JITTER_FRACTION = 0.2;

    private final Clock clock;
    private final DoubleSupplier jitterSource;
    private final RedisPersistenceService redisPersistenceService;
    private final DataRetentionProperties retentionProperties;
    private final Map<String, RecoveryState> states = new ConcurrentHashMap<>();

    @Autowired
    public ModelRecoveryTracker(RedisPersistenceService redisPersistenceService,
                                DataRetentionProperties retentionProperties) {
        this(Clock.systemUTC(), () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(),
                redisPersistenceService, retentionProperties);
    }

    public ModelRecoveryTracker() {
        this(Clock.systemUTC(), () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(), null, null);
    }

    ModelRecoveryTracker(Clock clock, DoubleSupplier jitterSource) {
        this(clock, jitterSource, null, null);
    }

    private ModelRecoveryTracker(Clock clock, DoubleSupplier jitterSource,
                                 RedisPersistenceService redisPersistenceService,
                                 DataRetentionProperties retentionProperties) {
        this.clock = clock;
        this.jitterSource = jitterSource;
        this.redisPersistenceService = redisPersistenceService;
        this.retentionProperties = retentionProperties;
    }

    public RecoveryState recordFailure(String modelId) {
        if (redisPersistenceService != null) {
            double jitterMultiplier = jitterMultiplier();
            Duration ttl = retentionProperties == null ? Duration.ofHours(24) : retentionProperties.getTtlDuration();
            RedisPersistenceService.RecoveryBackoff backoff = redisPersistenceService.recordModelRecoveryFailure(
                    modelId, INITIAL_BACKOFF_MS, MAX_BACKOFF_MS, jitterMultiplier, ttl);
            return new RecoveryState(backoff.failureCount(), backoff.nextProbeAt());
        }

        Instant now = clock.instant();
        return states.compute(modelId, (ignored, previous) -> {
            int failures = previous == null ? 1 : previous.failureCount() + 1;
            long baseDelay = baseDelayMs(failures);
            long delay = Math.max(1, Math.min(MAX_BACKOFF_MS, Math.round(baseDelay * jitterMultiplier())));
            return new RecoveryState(failures, now.plusMillis(delay));
        });
    }

    public void recordSuccess(String modelId) {
        if (redisPersistenceService != null) {
            redisPersistenceService.clearModelRecoveryBackoff(modelId);
        } else {
            states.remove(modelId);
        }
    }

    public boolean isDue(String modelId, Instant now) {
        RecoveryState state = getState(modelId);
        return state != null && !state.nextProbeAt().isAfter(now);
    }

    public RecoveryState getState(String modelId) {
        if (redisPersistenceService != null) {
            Optional<RedisPersistenceService.RecoveryBackoff> backoff =
                    redisPersistenceService.getModelRecoveryBackoff(modelId);
            return backoff.map(value -> new RecoveryState(value.failureCount(), value.nextProbeAt())).orElse(null);
        }
        return states.get(modelId);
    }

    public boolean hasState(String modelId) {
        return getState(modelId) != null;
    }

    static boolean isTransientModelFailure(Throwable error) {
        if (isProviderWideFailure(error)) return false;
        if (error instanceof ProviderFailureException failure) {
            return failure.getFailureType() == ProviderFailureType.TRANSIENT_UPSTREAM;
        }
        if (error instanceof LlmProviderClient.UpstreamServiceException upstreamFailure) {
            return upstreamFailure.getStatusCode() == 408 || upstreamFailure.getStatusCode() >= 500;
        }
        return false;
    }

    static boolean isProviderWideFailure(Throwable error) {
        return error instanceof ProviderFailureException failure && failure.isProviderWide()
                || error instanceof LlmProviderClient.RateLimitException;
    }

    private long baseDelayMs(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), 2);
        return INITIAL_BACKOFF_MS << shift;
    }

    private double jitterMultiplier() {
        double sample = Math.max(0.0, Math.min(1.0, jitterSource.getAsDouble()));
        return 1.0 - JITTER_FRACTION + (2.0 * JITTER_FRACTION * sample);
    }

    public record RecoveryState(int failureCount, Instant nextProbeAt) {
    }
}