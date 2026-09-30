package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.DataRetentionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisModelRecoveryPersistenceTest {
    private static final String FAILURES_KEY = "gateway:model:recovery:failures:";
    private static final String STATE_KEY = "gateway:model:recovery:state:";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisPersistenceService persistence;
    private static ModelRecoveryTracker firstTracker;
    private static ModelRecoveryTracker secondTracker;

    @BeforeAll
    static void connectToRedis() {
        String host = System.getenv("REDIS_TEST_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        DataRetentionProperties retention = new DataRetentionProperties();
        retention.setTtlHours(1);
        persistence = new RedisPersistenceService(redisTemplate,
                new ObjectMapper().findAndRegisterModules(), retention);
        firstTracker = new ModelRecoveryTracker(persistence, retention);
        secondTracker = new ModelRecoveryTracker(persistence, retention);
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void clearRecoveryKeys() {
        redisTemplate.delete(List.of(FAILURES_KEY + "shared-model", STATE_KEY + "shared-model",
                FAILURES_KEY + "ttl-model", STATE_KEY + "ttl-model"));
    }

    @Test
    void failureCountAndDueTimeAreSharedAcrossTrackerInstances() {
        ModelRecoveryTracker.RecoveryState first = firstTracker.recordFailure("shared-model");

        assertEquals(1, first.failureCount());
        assertTrue(secondTracker.hasState("shared-model"));
        assertEquals(first, secondTracker.getState("shared-model"));

        ModelRecoveryTracker.RecoveryState second = secondTracker.recordFailure("shared-model");

        assertEquals(2, second.failureCount());
        assertEquals(second, firstTracker.getState("shared-model"));
        assertTrue(second.nextProbeAt().isAfter(first.nextProbeAt()));
        assertTrue(redisTemplate.getExpire(FAILURES_KEY + "shared-model", TimeUnit.SECONDS) > 0);
        assertTrue(redisTemplate.getExpire(STATE_KEY + "shared-model", TimeUnit.SECONDS) > 0);
    }

    @Test
    void successfulRecoveryClearsSharedFailureState() {
        firstTracker.recordFailure("shared-model");
        secondTracker.recordFailure("shared-model");

        secondTracker.recordSuccess("shared-model");

        assertFalse(firstTracker.hasState("shared-model"));
        assertFalse(secondTracker.hasState("shared-model"));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(FAILURES_KEY + "shared-model")));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(STATE_KEY + "shared-model")));
    }

    @Test
    void failureAndBackoffKeysExpireUsingTheConfiguredRetentionTtl() throws InterruptedException {
        persistence.recordModelRecoveryFailure("ttl-model", ModelRecoveryTracker.INITIAL_BACKOFF_MS,
                ModelRecoveryTracker.MAX_BACKOFF_MS, 1.0, Duration.ofSeconds(2));

        long failuresTtl = redisTemplate.getExpire(FAILURES_KEY + "ttl-model", TimeUnit.SECONDS);
        long stateTtl = redisTemplate.getExpire(STATE_KEY + "ttl-model", TimeUnit.SECONDS);
        assertTrue(failuresTtl > 0 && failuresTtl <= 2, "failure counter should use the requested TTL");
        assertTrue(stateTtl > 0 && stateTtl <= 2, "backoff state should use the requested TTL");

        Thread.sleep(3_000);

        assertTrue(persistence.getModelRecoveryBackoff("ttl-model").isEmpty());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(FAILURES_KEY + "ttl-model")));
    }
}