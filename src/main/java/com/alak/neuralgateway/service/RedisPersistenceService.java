package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.DataRetentionProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for all Redis persistence operations.
 * Centralizes Redis key patterns and serialization logic.
 * All data automatically expires after configured TTL (default 24 hours).
 */
@Service
public class RedisPersistenceService {

    private static final String HISTORY_KEY_PREFIX = "gateway:model:history:";
    private static final String USAGE_KEY_PREFIX = "gateway:model:usage:";
    private static final String CIRCUIT_KEY_PREFIX = "gateway:model:circuit:";
    private static final String EMA_LATENCY_KEY_PREFIX = "gateway:model:ema_latency:";
    private static final String CONSECUTIVE_ERRORS_KEY_PREFIX = "gateway:model:consecutive_errors:";
    private static final String TPS_KEY_PREFIX = "gateway:model:tps:";
    private static final String REQUESTER_USAGE_KEY_PREFIX = "gateway:requester:usage:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DataRetentionProperties retentionProperties;
    private final int maxHistorySize;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisPersistenceService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, DataRetentionProperties retentionProperties) {
        this(redisTemplate, objectMapper, retentionProperties, 1440);
    }

    public RedisPersistenceService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                   DataRetentionProperties retentionProperties, int maxHistorySize) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.retentionProperties = retentionProperties;
        this.maxHistorySize = maxHistorySize;
    }

    private void setTtlIfNeeded(String key) {
        if (retentionProperties != null && retentionProperties.getTtlHours() > 0) {
            redisTemplate.expire(key, retentionProperties.getTtlHours(), TimeUnit.HOURS);
        }
    }

    // ==================== Health Check History ====================

    public void saveHealthCheckResult(String modelId, HealthCheckResult result) {
        String historyKey = HISTORY_KEY_PREFIX + modelId;
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForList().rightPush(historyKey, json);
            redisTemplate.opsForList().trim(historyKey, -maxHistorySize, -1);
            setTtlIfNeeded(historyKey);
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
        String key = USAGE_KEY_PREFIX + modelId;
        redisTemplate.opsForValue().increment(key);
        setTtlIfNeeded(key);
    }

    public long getUsage(String modelId) {
        String key = USAGE_KEY_PREFIX + modelId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ==================== Circuit Breaker State ====================

    public void setCircuitOpen(String modelId, boolean open) {
        String key = CIRCUIT_KEY_PREFIX + modelId;
        redisTemplate.opsForValue().set(key, String.valueOf(open));
        setTtlIfNeeded(key);
    }

    public boolean isCircuitOpen(String modelId) {
        String key = CIRCUIT_KEY_PREFIX + modelId;
        String value = redisTemplate.opsForValue().get(key);
        return Boolean.parseBoolean(value);
    }

    public void resetCircuit(String modelId) {
        String key = CIRCUIT_KEY_PREFIX + modelId;
        redisTemplate.delete(key);
    }

    // ==================== EMA Latency ====================

    public void saveEmaLatency(String modelId, double emaLatency) {
        String key = EMA_LATENCY_KEY_PREFIX + modelId;
        redisTemplate.opsForValue().set(key, String.valueOf(emaLatency));
        setTtlIfNeeded(key);
    }

    public double getEmaLatency(String modelId, double defaultValue) {
        String key = EMA_LATENCY_KEY_PREFIX + modelId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== Consecutive Errors ====================

    public void saveConsecutiveErrors(String modelId, int count) {
        String key = CONSECUTIVE_ERRORS_KEY_PREFIX + modelId;
        redisTemplate.opsForValue().set(key, String.valueOf(count));
        setTtlIfNeeded(key);
    }

    public int getConsecutiveErrors(String modelId) {
        String key = CONSECUTIVE_ERRORS_KEY_PREFIX + modelId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== TPS ====================

    public void saveTps(String modelId, double tps) {
        String key = TPS_KEY_PREFIX + modelId;
        redisTemplate.opsForValue().set(key, String.valueOf(tps));
        setTtlIfNeeded(key);
    }

    public double getTps(String modelId) {
        String key = TPS_KEY_PREFIX + modelId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ==================== Bulk Operations ====================

    public void initializeModelIfAbsent(String modelId) {
        String usageKey = USAGE_KEY_PREFIX + modelId;
        String circuitKey = CIRCUIT_KEY_PREFIX + modelId;
        String emaKey = EMA_LATENCY_KEY_PREFIX + modelId;
        String errorsKey = CONSECUTIVE_ERRORS_KEY_PREFIX + modelId;
        String tpsKey = TPS_KEY_PREFIX + modelId;

        redisTemplate.opsForValue().setIfAbsent(usageKey, "0");
        redisTemplate.opsForValue().setIfAbsent(circuitKey, "false");
        redisTemplate.opsForValue().setIfAbsent(emaKey, "0");
        redisTemplate.opsForValue().setIfAbsent(errorsKey, "0");
        redisTemplate.opsForValue().setIfAbsent(tpsKey, "0.0");

        setTtlIfNeeded(usageKey);
        setTtlIfNeeded(circuitKey);
        setTtlIfNeeded(emaKey);
        setTtlIfNeeded(errorsKey);
        setTtlIfNeeded(tpsKey);
    }

    // ==================== Requester Usage Tracking ====================

    public void incrementRequesterUsage(String requester) {
        incrementRequesterUsage(requester, 1);
    }

    public void incrementRequesterUsage(String requester, long tokens) {
        if (requester != null && !requester.isEmpty() && tokens > 0) {
            String key = REQUESTER_USAGE_KEY_PREFIX + requester;
            redisTemplate.opsForValue().increment(key, tokens);
            setTtlIfNeeded(key);
        }
    }

    public Map<String, Long> getRequesterUsage() {
        // Since we now use separate keys per requester, we need to scan
        // For better performance, we could maintain a set of requesters
        // For now, return empty map - would need pattern scan in production
        return new HashMap<>();
    }
}