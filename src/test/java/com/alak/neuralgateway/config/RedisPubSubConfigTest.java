package com.alak.neuralgateway.config;

import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.service.ModelStatusService;
import com.alak.neuralgateway.service.SseNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test verifying REDIS-001: Redis Pub/Sub subscriber deserialization and broadcast flow.
 */
class RedisPubSubConfigTest {

    private SseNotificationService sseNotificationService;
    private ModelStatusService modelStatusService;
    private com.alak.neuralgateway.service.RedisPersistenceService redisPersistenceService;
    private ObjectMapper objectMapper;
    private RedisPubSubConfig.SseMessageSubscriber subscriber;

    @BeforeEach
    void setUp() {
        sseNotificationService = mock(SseNotificationService.class);
        modelStatusService = mock(ModelStatusService.class);
        redisPersistenceService = mock(com.alak.neuralgateway.service.RedisPersistenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        subscriber = new RedisPubSubConfig.SseMessageSubscriber(
                sseNotificationService, modelStatusService, redisPersistenceService, objectMapper);
    }

    @Test
    void onMessage_validModelStatus_updatesRemoteAndBroadcasts() throws Exception {
        ModelStatus status = new ModelStatus(
                "test-model",
                List.of("CODING"),
                true,
                150L,
                Instant.now(),
                null,
                List.of(),
                10L,
                2,
                1.5,
                false,
                "nvidia",
                10,
                true
        );
        String messageJson = objectMapper.writeValueAsString(status);
        when(modelStatusService.getAllStatuses()).thenReturn(List.of(status));
        when(redisPersistenceService.getRequesterTelemetryDetailed()).thenReturn(List.of());

        subscriber.onMessage(messageJson, RedisPubSubConfig.SSE_STATUS_CHANNEL);

        ArgumentCaptor<ModelStatus> captor = ArgumentCaptor.forClass(ModelStatus.class);
        verify(modelStatusService, times(1)).updateFromRemote(captor.capture());
        assertEquals("test-model", captor.getValue().model());
        assertTrue(captor.getValue().isUp());
        assertEquals(150L, captor.getValue().latencyMs());

        verify(sseNotificationService, times(1)).broadcast(Map.of(
                "models", List.of(status),
                "requesters", List.of()
        ));
    }

    @Test
    void onMessage_malformedJson_handlesGracefullyWithoutException() {
        assertDoesNotThrow(() ->
                subscriber.onMessage("not a json string", RedisPubSubConfig.SSE_STATUS_CHANNEL));

        verify(modelStatusService, never()).updateFromRemote(any());
        verify(sseNotificationService, never()).broadcast(any());
    }
}
