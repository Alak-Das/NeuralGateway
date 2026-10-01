package com.alak.neuralgateway;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.model.Model.ModelCapabilities;
import com.alak.neuralgateway.service.ModelRegistry;
import com.alak.neuralgateway.service.ModelStatusProvider;
import com.alak.neuralgateway.service.RoutingService;
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
    private ModelStatusProvider modelStatusProvider;

    private RoutingProperties properties;
    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        properties.setContextWindowValidationEnabled(true);
        properties.setMaxFallbackAttempts(3);
        properties.setConnectionPenaltyMs(50); // expects int

        routingService = new RoutingService(properties, modelRegistry, modelStatusProvider);
    }

    @Test
    void testSelectModelsFiltersByContextWindow() {
        Model smallModel = new Model("small", "Small", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 8000, 1, new ModelCapabilities(false, false, false));
        Model largeModel = new Model("large", "Large", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 128000, 1, new ModelCapabilities(false, false, false));

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(smallModel, largeModel));
        lenient().when(modelRegistry.getModel("small")).thenReturn(java.util.Optional.of(smallModel));
        lenient().when(modelRegistry.getModel("large")).thenReturn(java.util.Optional.of(largeModel));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);

        // Request requires 10k tokens (small model should be filtered out)
        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 10000);

        assertEquals(1, candidates.size());
        assertEquals("large", candidates.get(0).getId());
    }

    @Test
    void testRoutingScorePenaltyForConnections() {
        Model model1 = new Model("m1", "M1", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 8000, 1, new ModelCapabilities(false, false, false));
        Model model2 = new Model("m2", "M2", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 8000, 1, new ModelCapabilities(false, false, false));

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(model1, model2));
        lenient().when(modelRegistry.getModel("m1")).thenReturn(java.util.Optional.of(model1));
        lenient().when(modelRegistry.getModel("m2")).thenReturn(java.util.Optional.of(model2));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);

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

    @Test
    void testEmergencyDegradedModeWhenAllCircuitsOpen() {
        Model model1 = new Model("m1", "M1", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 8000, 10, new ModelCapabilities(false, false, false));
        Model model2 = new Model("m2", "M2", "nvidia", java.util.Collections.singleton(Pipeline.CODING), 8000, 5, new ModelCapabilities(false, false, false));

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(model1, model2));
        lenient().when(modelRegistry.getModel("m1")).thenReturn(java.util.Optional.of(model1));
        lenient().when(modelRegistry.getModel("m2")).thenReturn(java.util.Optional.of(model2));

        // Both models have open circuit breakers and are down
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(false);
        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        // Emergency degraded mode must never return an empty list when models exist!
        assertFalse(candidates.isEmpty(), "Candidates must not be empty in degraded mode");
        assertEquals(2, candidates.size());
    }

    @Test
    void unhealthyModelsRemainAvailableAsFailoverCandidatesAfterHealthyModels() {
        Model healthy = new Model("healthy", "Healthy", "nvidia",
                java.util.Collections.singleton(Pipeline.CODING), 8000, 5,
                new ModelCapabilities(false, false, false));
        Model unhealthy = new Model("unhealthy", "Unhealthy", "nvidia",
                java.util.Collections.singleton(Pipeline.CODING), 8000, 10,
                new ModelCapabilities(false, false, false));
        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(unhealthy, healthy));
        when(modelStatusProvider.isModelUp("healthy")).thenReturn(true);
        when(modelStatusProvider.isModelUp("unhealthy")).thenReturn(false);

        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        assertEquals(List.of("healthy", "unhealthy"), candidates.stream().map(Model::getId).toList(),
                "Healthy models should lead, but unhealthy enabled models must remain available for failover");
    }

    @Test
    void candidateCountDoesNotExceedConfiguredFallbackLimit() {
        properties.setMaxFallbackAttempts(1);
        Model first = new Model("first", "First", "nvidia",
                java.util.Collections.singleton(Pipeline.CODING), 8000, 10,
                new ModelCapabilities(false, false, false));
        Model second = new Model("second", "Second", "nvidia",
                java.util.Collections.singleton(Pipeline.CODING), 8000, 9,
                new ModelCapabilities(false, false, false));
        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(first, second));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);

        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        assertEquals(List.of("first"), candidates.stream().map(Model::getId).toList());
    }

    @Test
    void decrementActiveConnectionsNeverMakesTheCountNegative() {
        routingService.decrementActiveConnections("m1");
        routingService.incrementActiveConnections("m1");
        routingService.decrementActiveConnections("m1");
        routingService.decrementActiveConnections("m1");

        assertEquals(0, routingService.getActiveConnections("m1"));
    }
}
