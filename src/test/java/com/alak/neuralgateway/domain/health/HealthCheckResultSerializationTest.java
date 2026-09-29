package com.alak.neuralgateway.domain.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckResultSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void redisJsonRoundTripPreservesUpStatus() throws Exception {
        HealthCheckResult result = new HealthCheckResult(
                "test-model",
                true,
                125,
                Instant.parse("2025-01-02T03:04:05Z"),
                null,
                true
        );

        String json = objectMapper.writeValueAsString(result);
        JsonNode serialized = objectMapper.readTree(json);
        HealthCheckResult restored = objectMapper.readValue(json, HealthCheckResult.class);

        assertTrue(serialized.get("up").asBoolean());
        assertFalse(serialized.has("isUp"));
        assertEquals(result.isUp(), restored.isUp());
        assertEquals(result.getLatencyMs(), restored.getLatencyMs());
        assertEquals(result.getTimestamp(), restored.getTimestamp());
        assertEquals(result.isBackgroundProbe(), restored.isBackgroundProbe());
    }

    @Test
    void deserializesLegacyRedisJsonWithIsUpProperty() throws Exception {
        String legacyJson = """
                {
                  "model": "test-model",
                  "isUp": true,
                  "latencyMs": 125,
                  "timestamp": "2025-01-02T03:04:05Z",
                  "errorMessage": null,
                  "isBackgroundProbe": false
                }
                """;

        HealthCheckResult restored = objectMapper.readValue(legacyJson, HealthCheckResult.class);

        assertNotNull(restored);
        assertTrue(restored.isUp());
        assertEquals("test-model", restored.getModel());
    }
}
