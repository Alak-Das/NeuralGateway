package com.example.llmservice.service;

import com.example.llmservice.domain.health.HealthCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for all Redis persistence operations.
 * Centralizes Redis key patterns and serialization logic.
 */
@Service
public class RedisPersistenceService {

    private static final String HISTORY_KEY_PREFIX = "gateway:model:history:";
    private static final String USAGE_KEY = "gateway:model:usage";
    private static final String CIRCUIT_KEY = "gateway:model:circuit";
    private static final String EMA_LATENCY_KEY = "gateway:model:ema_latency";
    private static final String CONSECUTIVE_ERRORS_KEY = "gateway:model:consecutive_errors";
    private static final String TPS_KEY = "gateway:model:tps";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxHistorySize;

    public RedisPersistenceService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, 1440); // Default 24 hours of 1-minute pings
    }

    public RedisPersistenceService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, int maxHistorySize) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxHistorySize = maxHistorySize;
    }

    // ==================== Health Check History ====================

    public void saveHealthCheckResult(String modelId, HealthCheckResult result) {
        String historyKey = HISTORY_KEY_PREFIX + modelId;
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForList().rightPush(historyKey, json);
            redisTemplate.opsForList().trim(historyKey, -maxHistorySize, -1);
        } catch (Exception e) {
            // Log but don't throw - persistence failure shouldn't break the flow
        }
    }

    public List<HealthCheckResult> getHealthCheckHistory(String modelId) {
        String historyKey = HISTORY_KEY_PREFIX + modelId;
        List<String> historyStrings = redisTemplate.opsForList().range(historyKey, 0, -1);
        if (historyStrings == null || historyStrings.isEmpty()) {
            return new ArrayList<>();
        }

        List<HealthCheckResult> history = new ArrayList<>();
        for (String json : historyStrings) {
            try {
                history.add(objectMapper.readValue(json, HealthCheckResult.class));
            } catch (Exception e) {
                // Skip invalid entries
            }
        }
        return history;
    }

    public Optional<HealthCheckResult> getLatestHealthCheck(String modelId) {
        String historyKey = HISTORY_KEY_PREFIX + modelId;
        String latestJson = redisTemplate.opsForList().index(historyKey, -1);
        if (latestJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(latestJson, HealthCheckResult.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ==================== Usage Tracking ====================

    public void incrementUsage(String modelId) {
        redisTemplate.opsForHash().increment(USAGE_KEY, modelId, 1);
    }

    public long getUsage(String modelId) {
        Object value = redisTemplate.opsForHash().get(USAGE_KEY, modelId);
        return value != null ? Long.parseLong(value.toString()) : 0L;
    }

    // ==================== Circuit Breaker State ====================

    public void setCircuitOpen(String modelId, boolean open) {
        redisTemplate.opsForHash().put(CIRCUIT_KEY, modelId, String.valueOf(open));
    }

    public boolean isCircuitOpen(String modelId) {
        Object value = redisTemplate.opsForHash().get(CIRCUIT_KEY, modelId);
        return value != null && Boolean.parseBoolean(value.toString());
    }

    public void resetCircuit(String modelId) {
        redisTemplate.opsForHash().delete(CIRCUIT_KEY, modelId);
    }

    // ==================== EMA Latency ====================

    public void saveEmaLatency(String modelId, double emaLatency) {
        redisTemplate.opsForHash().put(EMA_LATENCY_KEY, modelId, String.valueOf(emaLatency));
    }

    public double getEmaLatency(String modelId, double defaultValue) {
        Object value = redisTemplate.opsForHash().get(EMA_LATENCY_KEY, modelId);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== Consecutive Errors ====================

    public void saveConsecutiveErrors(String modelId, int count) {
        redisTemplate.opsForHash().put(CONSECUTIVE_ERRORS_KEY, modelId, String.valueOf(count));
    }

    public int getConsecutiveErrors(String modelId) {
        Object value = redisTemplate.opsForHash().get(CONSECUTIVE_ERRORS_KEY, modelId);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== TPS ====================

    public void saveTps(String modelId, double tps) {
        redisTemplate.opsForHash().put(TPS_KEY, modelId, String.valueOf(tps));
    }

    public double getTps(String modelId) {
        Object value = redisTemplate.opsForHash().get(TPS_KEY, modelId);
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ==================== Bulk Operations ====================

    public void initializeModelIfAbsent(String modelId) {
        // Ensure model has entries in all hashes
        if (!redisTemplate.hasKey(HISTORY_KEY_PREFIX + modelId)) {
            // History list will be created on first push
        }
        if (redisTemplate.opsForHash().get(USAGE_KEY, modelId) == null) {
            redisTemplate.opsForHash().putIfAbsent(USAGE_KEY, modelId, "0");
        }
        if (redisTemplate.opsForHash().get(CIRCUIT_KEY, modelId) == null) {
            redisTemplate.opsForHash().putIfAbsent(CIRCUIT_KEY, modelId, "false");
        }
        if (redisTemplate.opsForHash().get(EMA_LATENCY_KEY, modelId) == null) {
            redisTemplate.opsForHash().putIfAbsent(EMA_LATENCY_KEY, modelId, "0");
        }
        if (redisTemplate.opsForHash().get(CONSECUTIVE_ERRORS_KEY, modelId) == null) {
            redisTemplate.opsForHash().putIfAbsent(CONSECUTIVE_ERRORS_KEY, modelId, "0");
        }
        if (redisTemplate.opsForHash().get(TPS_KEY, modelId) == null) {
            redisTemplate.opsForHash().putIfAbsent(TPS_KEY, modelId, "0.0");
        }
    }
}