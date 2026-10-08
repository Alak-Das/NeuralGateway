package com.alak.neuralgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Service for structured audit logging of critical gateway operations.
 * Logs are emitted in structured format for easy parsing by log aggregation systems.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static String safeStr(String str) {
        return str != null ? str : "";
    }

    /**
     * Log a routing decision for traceability.
     */
    public void logRoutingDecision(String txId, String requester, String virtualModel,
                                    String selectedModel, String reason, int candidatesTried) {
        Map<String, Object> audit = Map.of(
            "event", "ROUTING_DECISION",
            "txId", safeStr(txId),
            "requester", safeStr(requester),
            "virtualModel", safeStr(virtualModel),
            "selectedModel", safeStr(selectedModel),
            "reason", safeStr(reason),
            "candidatesTried", candidatesTried,
            "timestamp", Instant.now().toString()
        );
        log.info("AUDIT: {}", audit);
    }

    /**
     * Log a failover event when a model fails and request is retried on another model.
     */
    public void logFailover(String txId, String fromModel, String toModel, String error) {
        Map<String, Object> audit = Map.of(
            "event", "FAILOVER",
            "txId", safeStr(txId),
            "fromModel", safeStr(fromModel),
            "toModel", safeStr(toModel),
            "error", safeStr(error),
            "timestamp", Instant.now().toString()
        );
        log.warn("AUDIT: {}", audit);
    }

    /**
     * Log circuit breaker state changes.
     */
    public void logCircuitBreakerEvent(String modelId, String event, String state) {
        Map<String, Object> audit = Map.of(
            "event", "CIRCUIT_BREAKER",
            "modelId", safeStr(modelId),
            "action", safeStr(event),
            "newState", safeStr(state),
            "timestamp", Instant.now().toString()
        );
        log.warn("AUDIT: {}", audit);
    }

    /**
     * Log model status changes (UP/DOWN).
     */
    public void logModelStatusChange(String modelId, boolean isUp, long latencyMs, String errorMessage) {
        Map<String, Object> audit = Map.of(
            "event", "MODEL_STATUS_CHANGE",
            "modelId", safeStr(modelId),
            "status", isUp ? "UP" : "DOWN",
            "latencyMs", latencyMs,
            "error", safeStr(errorMessage),
            "timestamp", Instant.now().toString()
        );
        log.info("AUDIT: {}", audit);
    }

    /**
     * Log rate limiting events.
     */
    public void logRateLimitEvent(String providerId, String modelId, String keyId, int remainingRequests) {
        Map<String, Object> audit = Map.of(
            "event", "RATE_LIMIT",
            "providerId", safeStr(providerId),
            "modelId", safeStr(modelId),
            "keyId", safeStr(keyId),
            "remainingRequests", remainingRequests,
            "timestamp", Instant.now().toString()
        );
        log.info("AUDIT: {}", audit);
    }

    /**
     * Log authentication/authorization failures.
     */
    public void logAuthFailure(String providerId, String modelId, int statusCode, String errorMessage) {
        Map<String, Object> audit = Map.of(
            "event", "AUTH_FAILURE",
            "providerId", safeStr(providerId),
            "modelId", safeStr(modelId),
            "statusCode", statusCode,
            "error", safeStr(errorMessage),
            "timestamp", Instant.now().toString()
        );
        log.warn("AUDIT: {}", audit);
    }
}