package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.CircuitBreakerProperties;
import com.alak.neuralgateway.domain.circuit.CircuitState;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Service implementing circuit breaker pattern for model failure isolation using Resilience4j.
 * Each model gets its own circuit breaker instance dynamically created via the registry.
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    private final CircuitBreakerProperties properties;
    private final RedisPersistenceService redisPersistence;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerService(CircuitBreakerProperties properties, RedisPersistenceService redisPersistence) {
        this.properties = properties;
        this.redisPersistence = redisPersistence;

        // Configure Resilience4j based on custom properties
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.getFailureThreshold())
                .minimumNumberOfCalls(properties.getFailureThreshold())
                .failureRateThreshold(100.0f) // 100% failure rate (i.e. all consecutive calls fail)
                .waitDurationInOpenState(Duration.ofMillis(properties.getResetTimeoutMs()))
                .permittedNumberOfCallsInHalfOpenState(properties.getSuccessThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(properties.isAutoRecoveryEnabled())
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
        
        // Add a registry event listener to attach state transition listeners to newly created CBs
        this.circuitBreakerRegistry.getEventPublisher().onEntryAdded(entryAddedEvent -> {
            CircuitBreaker cb = entryAddedEvent.getAddedEntry();
            cb.getEventPublisher().onStateTransition(event -> handleStateTransition(cb.getName(), event.getStateTransition()));
        });
    }

    private CircuitBreaker getCircuitBreaker(String modelId) {
        return circuitBreakerRegistry.circuitBreaker(modelId);
    }

    private void handleStateTransition(String modelId, CircuitBreaker.StateTransition transition) {
        switch (transition.getToState()) {
            case OPEN:
            case FORCED_OPEN:
            case HALF_OPEN:
                redisPersistence.setCircuitOpen(modelId, true);
                break;
            case CLOSED:
                redisPersistence.setCircuitOpen(modelId, false);
                redisPersistence.saveConsecutiveErrors(modelId, 0);
                break;
            default:
                break;
        }
    }

    /**
     * Initialize circuit breaker state for a model from Redis.
     */
    public void initializeModel(String modelId) {
        CircuitBreaker cb = getCircuitBreaker(modelId);
        boolean persistedOpen = redisPersistence.isCircuitOpen(modelId);
        CircuitBreaker.State state = cb.getState();
        if (persistedOpen && (state == CircuitBreaker.State.CLOSED || state == CircuitBreaker.State.HALF_OPEN)) {
            cb.transitionToOpenState();
        } else if (!persistedOpen && (state == CircuitBreaker.State.OPEN
                || state == CircuitBreaker.State.FORCED_OPEN || state == CircuitBreaker.State.HALF_OPEN)) {
            cb.transitionToClosedState();
        }
    }

    /**
     * Check if a request to the model should be permitted.
     */
    public boolean isRequestPermitted(String modelId) {
        return getCircuitBreaker(modelId).tryAcquirePermission();
    }

    /**
     * Record a successful request.
     */
    public void recordSuccess(String modelId) {
        long duration = 1; // dummy duration since we track outcome, not time
        getCircuitBreaker(modelId).onSuccess(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
        
        // Ensure Redis reflects closed state
        if (getCircuitBreaker(modelId).getState() == CircuitBreaker.State.CLOSED) {
            redisPersistence.saveConsecutiveErrors(modelId, 0);
        }
    }

    /**
     * Record a failed request.
     */
    public void recordFailure(String modelId, Throwable throwable) {
        long duration = 1;
        getCircuitBreaker(modelId).onError(duration, java.util.concurrent.TimeUnit.MILLISECONDS, throwable);
    }

    /**
     * Treat a verified healthy probe as authoritative and close the circuit.
     * <p>
     * A probe performs a real upstream call, so its success is strong evidence that the
     * model recovered. Without this, a breaker restored OPEN from Redis would stay OPEN
     * (blocking routing and the dashboard) until the passive half-open timeout elapsed.
     * Safe with resilience4j 2.2.0: OPEN, HALF_OPEN and FORCED_OPEN to CLOSED are all
     * legal transitions; the state guard simply avoids recreating the CLOSED state.
     */
    public void markHealthy(String modelId) {
        CircuitBreaker cb = getCircuitBreaker(modelId);
        if (cb.getState() != CircuitBreaker.State.CLOSED) {
            cb.transitionToClosedState();
            log.info("Circuit breaker for model '{}' closed after verified healthy probe", modelId);
        }
        // Keep Redis authoritative even when no state-transition event fired
        // (e.g. breaker already CLOSED while Redis still held OPEN=true).
        redisPersistence.setCircuitOpen(modelId, false);
        redisPersistence.saveConsecutiveErrors(modelId, 0);
    }

    /**
     * Release a request permission when a streaming caller disconnects before completion.
     */
    public void releasePermission(String modelId) {
        getCircuitBreaker(modelId).releasePermission();
    }

    /**
     * Manually reset the circuit breaker to CLOSED state.
     */
    public void resetCircuit(String modelId) {
        getCircuitBreaker(modelId).transitionToClosedState();
    }

    /**
     * Force open the circuit breaker (e.g., for maintenance).
     */
    public void forceOpen(String modelId) {
        getCircuitBreaker(modelId).transitionToForcedOpenState();
    }

    /**
     * Get current circuit state for a model.
     */
    public CircuitState getState(String modelId) {
        CircuitBreaker.State r4jState = getCircuitBreaker(modelId).getState();
        return switch (r4jState) {
            case OPEN, FORCED_OPEN -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
            case CLOSED, DISABLED, METRICS_ONLY -> CircuitState.CLOSED;
        };
    }

    /**
     * Check if circuit is OPEN (blocking requests).
     */
    public boolean isCircuitOpen(String modelId) {
        return getState(modelId) == CircuitState.OPEN;
    }

    /**
     * Check if circuit is in HALF_OPEN state.
     */
    public boolean isHalfOpen(String modelId) {
        return getState(modelId) == CircuitState.HALF_OPEN;
    }

    /**
     * Execute a supplier with circuit breaker protection.
     */
    public <T> T executeWithCircuitBreaker(String modelId, Supplier<T> supplier) throws Exception {
        if (!isRequestPermitted(modelId)) {
            throw new IllegalStateException("Circuit breaker is OPEN for model: " + modelId);
        }

        long start = System.currentTimeMillis();
        try {
            T result = supplier.get();
            getCircuitBreaker(modelId).onSuccess(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS);
            return result;
        } catch (Exception e) {
            getCircuitBreaker(modelId).onError(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS, e);
            throw e;
        }
    }
}
