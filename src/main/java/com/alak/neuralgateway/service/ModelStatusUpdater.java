package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.health.HealthCheckResult;

/**
 * Interface for updating model status from health check results.
 * Allows decoupling health check service from status storage/broadcast.
 */
public interface ModelStatusUpdater {
    /**
     * Update model status from a health check result.
     */
    void updateStatus(String modelId, HealthCheckResult result);
}