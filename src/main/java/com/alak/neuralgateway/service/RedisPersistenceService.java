package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.DataRetentionProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for all Redis persistence operations.
 * Centralizes Redis key patterns and serialization logic.
 * All data automatically expires after configured TTL (default 24 hours).
 */
@Service
public class RedisPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RedisPersistenceService.class);

    private static final String HISTORY_KEY_PREFIX = "gateway:model:history:";
    private static final String USAGE_KEY_PREFIX = "gateway:model:usage:";
    private static final String CIRCUIT_KEY_PREFIX = "gateway:model:circuit:";
    private static final String EMA_LATENCY_KEY_PREFIX = "gateway:model:ema_latency:";
    private static final String CONSECUTIVE_ERRORS_KEY_PREFIX = "gateway:model:consecutive_errors:";
    private static final String PROVIDER_CONSECUTIVE_ERRORS_KEY_PREFIX = "gateway:provider:consecutive_errors:";
    private static final String TPS_KEY_PREFIX = "gateway:model:tps:";
    private static final String REQUESTER_USAGE_KEY_PREFIX = "gateway:requester:usage:";
    private static final String PROVIDER_UNAVAILABLE_KEY_PREFIX = "gateway:provider:unavailable:";
    private static final String MODEL_RECOVERY_FAILURES_KEY_PREFIX = "gateway:model:recovery:failures:";
    private static final String MODEL_RECOVERY_STATE_KEY_PREFIX = "gateway:model:recovery:state:";
    private static final DefaultRedisScript<List> RECORD_RECOVERY_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local failures = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            local shift = math.min(math.max(failures - 1, 0), 2)
            local baseDelay = math.min(tonumber(ARGV[4]), tonumber(ARGV[3]) * (2 ^ shift))
            local delay = math.max(1, math.min(tonumber(ARGV[4]), math.floor(baseDelay * tonumber(ARGV[5]) + 0.5)))
            local nextProbeAt = tonumber(ARGV[2]) + delay
            redis.call('SET', KEYS[2], string.format('%d', failures) .. '|' .. string.format('%.0f', nextProbeAt), 'EX', ARGV[1])
            return { string.format('%d', failures), string.format('%.0f', nextProbeAt) }
            """, List.class);

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
            log.debug("Saving health-check history to Redis: modelId={}, up={}, latencyMs={}",
                    modelId, result.isUp(), result.getLatencyMs());
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

    public int incrementProviderConsecutiveErrors(String providerId) {
        if (providerId == null || providerId.isBlank()) return 0;
        String key = PROVIDER_CONSECUTIVE_ERRORS_KEY_PREFIX + providerId;
        Long count = redisTemplate.opsForValue().increment(key);
        setTtlIfNeeded(key);
        return count != null ? count.intValue() : 0;
    }

    public void resetProviderConsecutiveErrors(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            redisTemplate.delete(PROVIDER_CONSECUTIVE_ERRORS_KEY_PREFIX + providerId);
        }
    }

    public int getProviderConsecutiveErrors(String providerId) {
        if (providerId == null || providerId.isBlank()) return 0;
        String key = PROVIDER_CONSECUTIVE_ERRORS_KEY_PREFIX + providerId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) return 0;
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

    // ==================== Dynamic Config ====================

    private static final String MODEL_CONFIG_KEY = "llm:config:model_overrides";

    public void saveModelConfig(String modelId, String configJson) {
        redisTemplate.opsForHash().put(MODEL_CONFIG_KEY, modelId, configJson);
    }

    public Map<String, String> getAllModelConfigs() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(MODEL_CONFIG_KEY);
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return result;
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
        Map<String, Long> usageMap = new HashMap<>();
        try {
            // Use Redis SCAN to find all requester usage keys (non-blocking)
            String pattern = REQUESTER_USAGE_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys != null && !keys.isEmpty()) {
                for (String key : keys) {
                    String value = redisTemplate.opsForValue().get(key);
                    if (value != null) {
                        try {
                            // Extract requester name from key (remove prefix)
                            String requester = key.substring(REQUESTER_USAGE_KEY_PREFIX.length());
                            long count = Long.parseLong(value);
                            usageMap.put(requester, count);
                        } catch (NumberFormatException e) {
                            // Skip invalid values
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log but don't throw - persistence failure shouldn't break the flow
        }
        return usageMap;
    }

    public void setProviderUnavailable(String providerId, String reason, java.time.Duration cooldown) {
        if (providerId == null || providerId.isBlank() || cooldown == null || cooldown.isZero() || cooldown.isNegative())
            return;
        redisTemplate.opsForValue().set(PROVIDER_UNAVAILABLE_KEY_PREFIX + providerId, reason, cooldown);
    }

    public String getProviderUnavailableReason(String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        return redisTemplate.opsForValue().get(PROVIDER_UNAVAILABLE_KEY_PREFIX + providerId);
    }

    public void clearProviderUnavailable(String providerId) {
        if (providerId != null && !providerId.isBlank())
            redisTemplate.delete(PROVIDER_UNAVAILABLE_KEY_PREFIX + providerId);
    }

    /**
     * Atomically increments recovery failures and stores the next probe time so
     * every gateway replica observes the same backoff. Redis expires both values
     * after the configured state-retention period.
     */
    public RecoveryBackoff recordModelRecoveryFailure(String modelId, long initialBackoffMs,
                                                      long maxBackoffMs, double jitterMultiplier,
                                                      Duration retention) {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId is required");
        Duration ttl = retention == null || retention.isZero() || retention.isNegative()
                ? Duration.ofHours(24) : retention;
        String failuresKey = MODEL_RECOVERY_FAILURES_KEY_PREFIX + modelId;
        String stateKey = MODEL_RECOVERY_STATE_KEY_PREFIX + modelId;
        List<?> result = redisTemplate.execute(RECORD_RECOVERY_FAILURE_SCRIPT,
                List.of(failuresKey, stateKey), String.valueOf(Math.max(1, ttl.toSeconds())),
                String.valueOf(System.currentTimeMillis()), String.valueOf(initialBackoffMs),
                String.valueOf(maxBackoffMs), String.valueOf(jitterMultiplier));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("Redis did not return model recovery state");
        }
        return new RecoveryBackoff(Math.toIntExact(numberValue(result.get(0))),
                Instant.ofEpochMilli(numberValue(result.get(1))));
    }

    public Optional<RecoveryBackoff> getModelRecoveryBackoff(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        String value = redisTemplate.opsForValue().get(MODEL_RECOVERY_STATE_KEY_PREFIX + modelId);
        if (value == null) return Optional.empty();
        try {
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) return Optional.empty();
            return Optional.of(new RecoveryBackoff(Integer.parseInt(parts[0]),
                    Instant.ofEpochMilli(Long.parseLong(parts[1]))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public void clearModelRecoveryBackoff(String modelId) {
        if (modelId == null || modelId.isBlank()) return;
        redisTemplate.delete(List.of(MODEL_RECOVERY_FAILURES_KEY_PREFIX + modelId,
                MODEL_RECOVERY_STATE_KEY_PREFIX + modelId));
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        String text = value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        return Long.parseLong(text);
    }

    public record RecoveryBackoff(int failureCount, Instant nextProbeAt) {
    }
}
