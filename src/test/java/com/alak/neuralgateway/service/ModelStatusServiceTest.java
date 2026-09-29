package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelStatusServiceTest {

    @Test
    void updateStatusPersistsResultAndAddsItToStatusHistory() {
        RedisPersistenceService redisPersistence = mock(RedisPersistenceService.class);
        RoutingService routingService = mock(RoutingService.class);
        CircuitBreakerService circuitBreakerService = mock(CircuitBreakerService.class);
        ModelRegistry modelRegistry = mock(ModelRegistry.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        when(redisPersistence.getHealthCheckHistory("test-model")).thenReturn(new ArrayList<>());
        when(redisPersistence.getUsage("test-model")).thenReturn(0L);
        when(redisPersistence.isCircuitOpen("test-model")).thenReturn(false);
        when(redisPersistence.getEmaLatency("test-model", 0.0)).thenReturn(0.0);
        when(redisPersistence.getConsecutiveErrors("test-model")).thenReturn(0);
        when(modelRegistry.getModel("test-model")).thenReturn(Optional.empty());
        when(routingService.getActiveConnections("test-model")).thenReturn(0);
        when(routingService.getTps("test-model")).thenReturn(0.0);

        ModelStatusService service = new ModelStatusService(
                redisPersistence,
                routingService,
                circuitBreakerService,
                modelRegistry,
                new ObjectMapper().findAndRegisterModules(),
                eventPublisher
        );
        service.initializeModel("test-model");

        HealthCheckResult result = new HealthCheckResult(
                "test-model", true, 42, Instant.parse("2025-01-02T03:04:05Z"), null);
        service.updateStatus("test-model", result);

        verify(redisPersistence).saveHealthCheckResult("test-model", result);
        ModelStatus status = service.getStatus("test-model");
        assertTrue(status.isUp());
        assertEquals(1, status.history().size());
        assertEquals(result, status.history().get(0));
    }
}
