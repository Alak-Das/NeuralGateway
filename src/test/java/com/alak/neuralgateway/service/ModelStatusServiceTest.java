package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.model.Model.ModelCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ModelStatusServiceTest {

    private RedisPersistenceService redisPersistence;
    private RoutingService routingService;
    private ModelRegistry modelRegistry;
    private ObjectMapper objectMapper;
    private ApplicationEventPublisher eventPublisher;

    private ModelStatusService modelStatusService;
    private Model testModel;

    @BeforeEach
    void setUp() {
        redisPersistence = mock(RedisPersistenceService.class);
        routingService = mock(RoutingService.class);
        modelRegistry = mock(ModelRegistry.class);
        objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);

        testModel = new Model("test-model", "Test Model", "provider-1", Set.of(Pipeline.CODING), 8192, 10, ModelCapabilities.NONE);
        when(modelRegistry.getModel("test-model")).thenReturn(Optional.of(testModel));
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("test-model"));

        modelStatusService = new ModelStatusService(redisPersistence, routingService, modelRegistry, objectMapper, eventPublisher);
    }

    @Test
    void updateStatus_realRequestSingleError_remainsUpDueToAntiFlapping() {
        // Initial success to mark it UP
        when(redisPersistence.getConsecutiveErrors("test-model")).thenReturn(0);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", true, 50, Instant.now(), null, false));
        assertTrue(modelStatusService.isModelUp("test-model"));
        assertFalse(modelStatusService.getStatus("test-model").circuitOpen());

        clearInvocations(redisPersistence);

        // 1st transient real-request error
        when(redisPersistence.incrementConsecutiveErrors("test-model")).thenReturn(1);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", false, 500, Instant.now(), "Connection reset", false));

        // Model must still be UP and circuit closed
        assertTrue(modelStatusService.isModelUp("test-model"), "Model should remain UP after 1 transient error");
        assertFalse(modelStatusService.getStatus("test-model").circuitOpen());
        verify(redisPersistence, never()).resetConsecutiveErrors("test-model");
    }

    @Test
    void updateStatus_realRequestThreeErrors_tripsCircuitBreakerAndMarksDown() {
        // Initial success
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", true, 50, Instant.now(), null, false));
        assertTrue(modelStatusService.isModelUp("test-model"));

        // 1st error
        when(redisPersistence.incrementConsecutiveErrors("test-model")).thenReturn(1);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", false, 500, Instant.now(), "Error 1", false));
        assertTrue(modelStatusService.isModelUp("test-model"));

        // 2nd error
        when(redisPersistence.incrementConsecutiveErrors("test-model")).thenReturn(2);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", false, 500, Instant.now(), "Error 2", false));
        assertTrue(modelStatusService.isModelUp("test-model"));

        // 3rd error -> trips threshold
        when(redisPersistence.incrementConsecutiveErrors("test-model")).thenReturn(3);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", false, 500, Instant.now(), "Error 3", false));
        assertFalse(modelStatusService.isModelUp("test-model"), "Model should be DOWN after 3 consecutive errors");
        assertTrue(modelStatusService.getStatus("test-model").circuitOpen());
        assertEquals("Error 3", modelStatusService.getErrorMessage("test-model"));
    }

    @Test
    void updateStatus_backgroundProbeFailure_immediatelyMarksDown() {
        // Background synthetic probe failure should mark model DOWN immediately
        HealthCheckResult probeFailure = new HealthCheckResult("test-model", false, 1000, Instant.now(), "503 Probe failed", true);
        modelStatusService.updateStatus("test-model", probeFailure);

        assertFalse(modelStatusService.isModelUp("test-model"));
        assertTrue(modelStatusService.getStatus("test-model").circuitOpen());
        assertEquals("503 Probe failed", modelStatusService.getErrorMessage("test-model"));
    }

    @Test
    void updateStatus_successResetsConsecutiveErrorsAndRestoresUp() {
        // Set model down initially
        when(redisPersistence.incrementConsecutiveErrors("test-model")).thenReturn(3);
        modelStatusService.updateStatus("test-model", new HealthCheckResult("test-model", false, 500, Instant.now(), "Error", false));
        assertFalse(modelStatusService.isModelUp("test-model"));

        // Success restores UP
        HealthCheckResult success = new HealthCheckResult("test-model", true, 100, Instant.now(), null, true);
        modelStatusService.updateStatus("test-model", success);

        assertTrue(modelStatusService.isModelUp("test-model"));
        assertFalse(modelStatusService.getStatus("test-model").circuitOpen());
        assertNull(modelStatusService.getErrorMessage("test-model"));
        verify(redisPersistence).resetConsecutiveErrors("test-model");
    }
}
