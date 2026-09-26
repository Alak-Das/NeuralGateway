package com.example.llmservice;

import com.example.llmservice.config.CircuitBreakerProperties;
import com.example.llmservice.domain.circuit.CircuitState;
import com.example.llmservice.service.CircuitBreakerService;
import com.example.llmservice.service.RedisPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CircuitBreakerServiceTest {

    @Mock
    private RedisPersistenceService redisService;

    private CircuitBreakerProperties properties;
    private CircuitBreakerService circuitBreakerService;

    @BeforeEach
    void setUp() {
        properties = new CircuitBreakerProperties();
        properties.setFailureThreshold(3);
        properties.setResetTimeoutMs(30000);

        circuitBreakerService = new CircuitBreakerService(properties, redisService);
        circuitBreakerService.initializeModel("test-model");
    }

    @Test
    void testRecordSuccessResetsFailures() {
        String modelId = "test-model";
        circuitBreakerService.recordSuccess(modelId);
        
        verify(redisService).saveConsecutiveErrors(modelId, 0);
    }

    @Test
    void testRecordFailureTripsCircuitWhenThresholdReached() {
        String modelId = "test-model";
        
        // Record 3 failures (threshold)
        circuitBreakerService.recordFailure(modelId, new RuntimeException("Error 1"));
        circuitBreakerService.recordFailure(modelId, new RuntimeException("Error 2"));
        circuitBreakerService.recordFailure(modelId, new RuntimeException("Error 3"));
        
        // Circuit should trip
        verify(redisService, atLeastOnce()).setCircuitOpen(modelId, true);
        assertEquals(CircuitState.OPEN, circuitBreakerService.getState(modelId));
    }

    @Test
    void testIsCircuitOpen() {
        String modelId = "test-model";
        circuitBreakerService.forceOpen(modelId);
        
        assertTrue(circuitBreakerService.isCircuitOpen(modelId));
    }
}
