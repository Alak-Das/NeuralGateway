package com.example.llmservice.service;

import com.example.llmservice.config.CircuitBreakerProperties;
import com.example.llmservice.domain.circuit.CircuitState;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Service implementing circuit breaker pattern for model failure isolation.
 * Each model gets its own circuit breaker state machine.
 */
@Service
public class CircuitBreakerService {

    private final CircuitBreakerProperties properties;
    private final RedisPersistenceService redisPersistence;

    // In-memory state for fast access
    private final ConcurrentHashMap<String, AtomicReference<CircuitState>> circuitStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveSuccesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastFailureTime = new ConcurrentHashMap<>();

    public CircuitBreakerService(CircuitBreakerProperties properties, RedisPersistenceService redisPersistence) {
        this.properties = properties;
        this.redisPersistence = redisPersistence;
    }

    /**
     * Initialize circuit breaker state for a model from Redis.
     */
    public void initializeModel(String modelId) {
        boolean persistedOpen = redisPersistence.isCircuitOpen(modelId);
        CircuitState initialState = persistedOpen ? CircuitState.OPEN : CircuitState.CLOSED;
        circuitStates.put(modelId, new AtomicReference<>(initialState));
        consecutiveFailures.putIfAbsent(modelId, new AtomicInteger(redisPersistence.getConsecutiveErrors(modelId)));
        consecutiveSuccesses.putIfAbsent(modelId, new AtomicInteger(0));
        lastFailureTime.putIfAbsent(modelId, new AtomicLong(0));
    }

    /**
     * Check if a request to the model should be permitted.
     * @return true if request can proceed, false if circuit is OPEN and should fail fast
     */
    public boolean isRequestPermitted(String modelId) {
        CircuitState state = getState(modelId);
        
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                // Check if we should transition to HALF_OPEN
                if (shouldAttemptReset(modelId)) {
                    transitionToHalfOpen(modelId);
                    return true;
                }
                return false;
            case HALF_OPEN:
                // Allow limited requests through in HALF_OPEN
                return true;
            default:
                return true;
        }
    }

    /**
     * Record a successful request.
     */
    public void recordSuccess(String modelId) {
        CircuitState state = getState(modelId);
        
        if (state == CircuitState.HALF_OPEN) {
            int successes = consecutiveSuccesses.get(modelId).incrementAndGet();
            if (successes >= properties.getSuccessThreshold()) {
                transitionToClosed(modelId);
            }
        } else if (state == CircuitState.CLOSED) {
            // Reset failure count on success
            consecutiveFailures.get(modelId).set(0);
            redisPersistence.saveConsecutiveErrors(modelId, 0);
        }
    }

    /**
     * Record a failed request.
     */
    public void recordFailure(String modelId, Throwable throwable) {
        CircuitState state = getState(modelId);
        
        if (state == CircuitState.HALF_OPEN) {
            // Any failure in HALF_OPEN trips back to OPEN
            transitionToOpen(modelId);
            return;
        }

        if (state == CircuitState.CLOSED) {
            int failures = consecutiveFailures.get(modelId).incrementAndGet();
            consecutiveSuccesses.get(modelId).set(0);
            lastFailureTime.get(modelId).set(System.currentTimeMillis());
            redisPersistence.saveConsecutiveErrors(modelId, failures);

            if (failures >= properties.getFailureThreshold()) {
                transitionToOpen(modelId);
            }
        }
    }

    /**
     * Manually reset the circuit breaker to CLOSED state.
     */
    public void resetCircuit(String modelId) {
        transitionToClosed(modelId);
    }

    /**
     * Force open the circuit breaker (e.g., for maintenance).
     */
    public void forceOpen(String modelId) {
        transitionToOpen(modelId);
    }

    /**
     * Get current circuit state for a model.
     */
    public CircuitState getState(String modelId) {
        return circuitStates.computeIfAbsent(modelId, k -> new AtomicReference<>(CircuitState.CLOSED)).get();
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
     * @param modelId The model identifier
     * @param supplier The operation to execute
     * @param <T> The result type
     * @return The supplier result
     * @throws IllegalStateException if circuit is OPEN
     * @throws Exception any exception from the supplier
     */
    public <T> T executeWithCircuitBreaker(String modelId, Supplier<T> supplier) throws Exception {
        if (!isRequestPermitted(modelId)) {
            throw new IllegalStateException("Circuit breaker is OPEN for model: " + modelId);
        }

        try {
            T result = supplier.get();
            recordSuccess(modelId);
            return result;
        } catch (Exception e) {
            recordFailure(modelId, e);
            throw e;
        }
    }

    // ==================== Private State Transition Methods ====================

    private boolean shouldAttemptReset(String modelId) {
        if (!properties.isAutoRecoveryEnabled()) {
            return false;
        }
        long lastFailure = lastFailureTime.getOrDefault(modelId, new AtomicLong(0)).get();
        return (System.currentTimeMillis() - lastFailure) >= properties.getResetTimeoutMs();
    }

    private void transitionToOpen(String modelId) {
        circuitStates.computeIfAbsent(modelId, k -> new AtomicReference<>(CircuitState.CLOSED))
                .set(CircuitState.OPEN);
        consecutiveSuccesses.get(modelId).set(0);
        lastFailureTime.get(modelId).set(System.currentTimeMillis());
        redisPersistence.setCircuitOpen(modelId, true);
    }

    private void transitionToHalfOpen(String modelId) {
        circuitStates.computeIfAbsent(modelId, k -> new AtomicReference<>(CircuitState.CLOSED))
                .set(CircuitState.HALF_OPEN);
        consecutiveSuccesses.get(modelId).set(0);
        redisPersistence.setCircuitOpen(modelId, true); // Still considered "open" in Redis
    }

    private void transitionToClosed(String modelId) {
        circuitStates.computeIfAbsent(modelId, k -> new AtomicReference<>(CircuitState.CLOSED))
                .set(CircuitState.CLOSED);
        consecutiveFailures.get(modelId).set(0);
        consecutiveSuccesses.get(modelId).set(0);
        redisPersistence.setCircuitOpen(modelId, false);
        redisPersistence.saveConsecutiveErrors(modelId, 0);
    }
}