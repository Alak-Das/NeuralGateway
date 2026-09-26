package com.example.llmservice.service;

/**
 * Interface for providing model status information to routing and other services.
 * Decouples consumers from the concrete status storage implementation.
 */
public interface ModelStatusProvider {
    /**
     * Check if a model is currently healthy/up.
     */
    boolean isModelUp(String modelId);

    /**
     * Get the current TPS (tokens per second) for a model.
     */
    double getTps(String modelId);

    /**
     * Get the current latency for a model.
     */
    long getLatency(String modelId);

    /**
     * Get the last check timestamp for a model.
     */
    java.time.Instant getLastChecked(String modelId);

    /**
     * Get the error message for a model (if any).
     */
    String getErrorMessage(String modelId);
}