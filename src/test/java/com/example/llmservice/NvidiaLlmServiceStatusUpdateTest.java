package com.example.llmservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class NvidiaLlmServiceStatusUpdateTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private NvidiaLlmService nvidiaLlmService;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        listOperations = Mockito.mock(ListOperations.class);
        hashOperations = Mockito.mock(HashOperations.class);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), anyString())).thenReturn(null);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        WebClient.Builder webClientBuilder = WebClient.builder();
        PayloadTelemetryService payloadTelemetryService = new PayloadTelemetryService("SUMMARY", 60);
        ToolCallNormalizer toolCallNormalizer = new ToolCallNormalizer(payloadTelemetryService);

        nvidiaLlmService = new NvidiaLlmService(
                redisTemplate,
                objectMapper,
                webClientBuilder,
                toolCallNormalizer,
                payloadTelemetryService,
                "https://integrate.api.nvidia.com/v1",
                "test-api-key",
                "test-model-1",
                "test-model-2",
                "test-model-3"
        );
    }

    @Test
    void testRecordModelSuccessMarksModelAsUp() {
        String model = "test-model-1";

        // Initially not yet checked or down
        nvidiaLlmService.recordModelSuccess(model, 350L);

        List<ModelStatus> statuses = nvidiaLlmService.getModelStatuses();
        ModelStatus status = statuses.stream().filter(s -> s.model().equals(model)).findFirst().orElseThrow();

        assertTrue(status.isUp(), "Model should be marked UP after a successful call");
        assertEquals(350L, status.latencyMs(), "Latency should match the recorded success latency");
        assertNull(status.errorMessage(), "ErrorMessage should be cleared on success");
        assertFalse(status.circuitOpen(), "Circuit breaker should be closed on success");
    }

    @Test
    void testRecordModelFailureMarksModelAsDown() {
        String model = "test-model-1";

        // First mark as UP
        nvidiaLlmService.recordModelSuccess(model, 300L);
        ModelStatus statusBefore = nvidiaLlmService.getModelStatuses().stream().filter(s -> s.model().equals(model)).findFirst().orElseThrow();
        assertTrue(statusBefore.isUp());

        // Then simulate an upstream failure (e.g. 503 Service Unavailable)
        Exception upstreamException = new RuntimeException("503 Service Unavailable from POST https://integrate.api.nvidia.com/v1/chat/completions");
        nvidiaLlmService.recordModelFailure(model, 1500L, upstreamException);

        List<ModelStatus> statuses = nvidiaLlmService.getModelStatuses();
        ModelStatus statusAfter = statuses.stream().filter(s -> s.model().equals(model)).findFirst().orElseThrow();

        assertFalse(statusAfter.isUp(), "Model should be marked DOWN after a valid failure");
        assertNotNull(statusAfter.errorMessage(), "ErrorMessage should be set on failure");
        assertTrue(statusAfter.errorMessage().contains("503 Service Unavailable"), "Error message should reflect failure cause");
    }

    @Test
    void testViceVersaRecoveryFromDownToUp() {
        String model = "test-model-2";

        // Simulate failure marking it DOWN
        nvidiaLlmService.recordModelFailure(model, 2000L, new RuntimeException("Connection Timeout"));
        ModelStatus downStatus = nvidiaLlmService.getModelStatuses().stream().filter(s -> s.model().equals(model)).findFirst().orElseThrow();
        assertFalse(downStatus.isUp());
        assertEquals("Connection Timeout", downStatus.errorMessage());

        // Now model processes a request successfully -> should recover to UP
        nvidiaLlmService.recordModelSuccess(model, 420L);
        ModelStatus recoveredStatus = nvidiaLlmService.getModelStatuses().stream().filter(s -> s.model().equals(model)).findFirst().orElseThrow();
        assertTrue(recoveredStatus.isUp(), "Model previously DOWN must transition to UP upon successful request");
        assertNull(recoveredStatus.errorMessage(), "Error message should be cleared");
        assertEquals(420L, recoveredStatus.latencyMs());
    }
}
