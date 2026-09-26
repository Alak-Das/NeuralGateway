package com.example.llmservice;

import com.example.llmservice.config.RoutingProperties;
import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import com.example.llmservice.domain.model.Model.ModelCapabilities;
import com.example.llmservice.service.CircuitBreakerService;
import com.example.llmservice.service.ModelRegistry;
import com.example.llmservice.service.ModelStatusProvider;
import com.example.llmservice.service.RoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoutingServiceTest {

    @Mock
    private ModelRegistry modelRegistry;
    @Mock
    private CircuitBreakerService circuitBreakerService;
    @Mock
    private ModelStatusProvider modelStatusProvider;

    private RoutingProperties properties;
    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        properties.setContextWindowValidationEnabled(true);
        properties.setMaxFallbackAttempts(3);
        properties.setConnectionPenaltyMs(50); // expects int

        routingService = new RoutingService(properties, modelRegistry, circuitBreakerService, modelStatusProvider);
    }

    @Test
    void testSelectModelsFiltersByContextWindow() {
        Model smallModel = new Model("small", "Small", Pipeline.CODING, 8000, new ModelCapabilities(false, false, false));
        Model largeModel = new Model("large", "Large", Pipeline.CODING, 128000, new ModelCapabilities(false, false, false));

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(smallModel, largeModel));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);
        when(circuitBreakerService.isCircuitOpen(anyString())).thenReturn(false);

        // Request requires 10k tokens (small model should be filtered out)
        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 10000);

        assertEquals(1, candidates.size());
        assertEquals("large", candidates.get(0).getId());
    }

    @Test
    void testRoutingScorePenaltyForConnections() {
        Model model1 = new Model("m1", "M1", Pipeline.CODING, 8000, new ModelCapabilities(false, false, false));
        Model model2 = new Model("m2", "M2", Pipeline.CODING, 8000, new ModelCapabilities(false, false, false));

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(model1, model2));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);
        when(circuitBreakerService.isCircuitOpen(anyString())).thenReturn(false);

        // Initialize maps
        routingService.initializeModel("m1");
        routingService.initializeModel("m2");

        // Set identical latency
        routingService.updateEmaLatency("m1", 100);
        routingService.updateEmaLatency("m2", 100);

        // Give m1 more active connections
        routingService.incrementActiveConnections("m1");

        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        // m2 should be selected first because m1 has active connections penalty
        assertEquals("m2", candidates.get(0).getId());
        assertEquals("m1", candidates.get(1).getId());
    }
}
