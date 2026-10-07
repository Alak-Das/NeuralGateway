package com.alak.neuralgateway.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled task that calculates and updates the TPS for all registered models.
 */
@Service
public class TpsCalculationTask {

    private final ModelRegistry modelRegistry;
    private final RedisPersistenceService redisPersistence;
    private final ModelStatusService modelStatusService;

    // Tracks the usage count from the previous calculation window
    private final Map<String, Long> previousUsageMap = new ConcurrentHashMap<>();

    public TpsCalculationTask(
            ModelRegistry modelRegistry,
            RedisPersistenceService redisPersistence,
            ModelStatusService modelStatusService) {
        this.modelRegistry = modelRegistry;
        this.redisPersistence = redisPersistence;
        this.modelStatusService = modelStatusService;
    }

    @Scheduled(fixedRateString = "${llm.tps.intervalMs:5000}")
    public void calculateTps() {
        // Calculate TPS over a 5-second window
        long windowSeconds = 5;
        double smoothingFactor = 0.3; // EMA smoothing factor
        
        for (String modelId : modelRegistry.getAllModelIds()) {
            long currentUsage = redisPersistence.getUsage(modelId);
            long previousUsage = previousUsageMap.getOrDefault(modelId, currentUsage);
            
            // Calculate instantaneous requests per second for this window
            long delta = currentUsage - previousUsage;
            double instantTps = Math.max(0.0, (double) delta / windowSeconds);
            
            // Apply Exponential Moving Average (EMA)
            double previousEmaTps = modelStatusService.getTps(modelId);
            double emaTps = (instantTps * smoothingFactor) + (previousEmaTps * (1.0 - smoothingFactor));
            
            // Round very small values to 0 to prevent infinite decay tails
            if (emaTps < 0.01) {
                emaTps = 0.0;
            }
            
            // Update state
            previousUsageMap.put(modelId, currentUsage);
            if (Double.compare(previousEmaTps, emaTps) != 0 || delta > 0) {
                modelStatusService.updateTps(modelId, emaTps);
            }
        }
    }
}
