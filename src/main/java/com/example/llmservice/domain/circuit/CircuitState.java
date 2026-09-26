package com.example.llmservice.domain.circuit;

/**
 * Circuit breaker states following the standard pattern:
 * CLOSED - Normal operation, requests pass through
 * OPEN - Failure threshold exceeded, requests fail fast
 * HALF_OPEN - Testing if service recovered, limited requests allowed
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}