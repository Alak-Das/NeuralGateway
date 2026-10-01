package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.HealthCheckProperties;
import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.model.Model.ModelCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRegistryDisabledTest {

    // ===== ModelRegistry =====

    @Test
    void disabledModelIsRegisteredButMarkedDisabled() {
        LlmProvidersProperties props = new LlmProvidersProperties();
        LlmProvidersProperties.ProviderConfig provider = new LlmProvidersProperties.ProviderConfig();
        provider.setKeys(List.of("k"));

        LlmProvidersProperties.ModelConfig enabledModel = new LlmProvidersProperties.ModelConfig();
        enabledModel.setId("enabled-model");
        enabledModel.setPipelines(Set.of(Pipeline.CODING));
        enabledModel.setPriority(10);
        enabledModel.setEnabled(true);

        LlmProvidersProperties.ModelConfig disabledModel = new LlmProvidersProperties.ModelConfig();
        disabledModel.setId("disabled-model");
        disabledModel.setPipelines(Set.of(Pipeline.CODING));
        disabledModel.setPriority(10);
        disabledModel.setEnabled(false);

        provider.setModels(List.of(enabledModel, disabledModel));
        props.setProviders(Map.of("provider-one", provider));

        ModelRegistry registry = new ModelRegistry(props);

        assertTrue(registry.getAllModelIds().contains("disabled-model"),
                "Disabled model must appear in catalog");
        assertTrue(registry.getAllModelIds().contains("enabled-model"),
                "Enabled model must appear in catalog");

        Model disabled = registry.getModel("disabled-model").orElseThrow();
        assertFalse(disabled.isEnabled(), "Disabled model must report isEnabled() == false");

        Model enabled = registry.getModel("enabled-model").orElseThrow();
        assertTrue(enabled.isEnabled(), "Enabled model must report isEnabled() == true");
    }

    @Test
    void modelDefaultEnabledTrueWhenNotSpecified() {
        LlmProvidersProperties.ModelConfig model = new LlmProvidersProperties.ModelConfig();
        model.setId("default-model");
        model.setPipelines(Set.of(Pipeline.CODING));
        // No call to setEnabled()

        assertTrue(model.isEnabled(), "ModelConfig.isEnabled() must default to true for backward compatibility");
    }

    @Test
    void registryRespectsDefaultEnabledTrue() {
        LlmProvidersProperties props = new LlmProvidersProperties();
        LlmProvidersProperties.ProviderConfig provider = new LlmProvidersProperties.ProviderConfig();
        provider.setKeys(List.of("k"));

        LlmProvidersProperties.ModelConfig model = new LlmProvidersProperties.ModelConfig();
        model.setId("default-model");
        model.setPipelines(Set.of(Pipeline.CODING));
        model.setPriority(10);
        // No setEnabled() call

        provider.setModels(List.of(model));
        props.setProviders(Map.of("provider-one", provider));

        ModelRegistry registry = new ModelRegistry(props);

        Model registered = registry.getModel("default-model").orElseThrow();
        assertTrue(registered.isEnabled(), "Model without explicit enabled: false must be enabled by default");
    }

    // ===== RoutingService =====

    @Test
    void routingExcludesDisabledModels() {
        ModelRegistry modelRegistry = mock(ModelRegistry.class);
        ModelStatusProvider modelStatusProvider = mock(ModelStatusProvider.class);

        Model enabled = model("enabled-model", "provider-one", 10, true);
        Model disabled = model("disabled-model", "provider-one", 5, false);

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(enabled, disabled));
        lenient().when(modelRegistry.getModel("enabled-model")).thenReturn(Optional.of(enabled));
        lenient().when(modelRegistry.getModel("disabled-model")).thenReturn(Optional.of(disabled));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(true);

        RoutingProperties properties = new RoutingProperties();
        properties.setContextWindowValidationEnabled(true);
        properties.setMaxFallbackAttempts(3);
        properties.setConnectionPenaltyMs(50);

        RoutingService routingService = new RoutingService(properties, modelRegistry, modelStatusProvider);

        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        assertEquals(List.of("enabled-model"), candidates.stream().map(Model::getId).toList(),
                "Only enabled models must be routed");
    }

    @Test
    void routingNeverSelectsDisabledModelEvenInDegradedMode() {
        ModelRegistry modelRegistry = mock(ModelRegistry.class);
        ModelStatusProvider modelStatusProvider = mock(ModelStatusProvider.class);

        // Only a disabled model in the pipeline
        Model disabled = model("disabled-model", "provider-one", 10, false);

        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(disabled));
        lenient().when(modelRegistry.getModel("disabled-model")).thenReturn(Optional.of(disabled));
        when(modelStatusProvider.isModelUp(anyString())).thenReturn(false);

        RoutingProperties properties = new RoutingProperties();
        properties.setContextWindowValidationEnabled(true);
        properties.setMaxFallbackAttempts(3);
        properties.setConnectionPenaltyMs(50);

        RoutingService routingService = new RoutingService(properties, modelRegistry, modelStatusProvider);

        List<Model> candidates = routingService.selectModels(Pipeline.CODING, 100);

        assertTrue(candidates.isEmpty(),
                "Disabled model must never be routed, even in emergency degraded mode");
    }

    // ===== HealthCheckService =====

    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final LlmProviderClient llmProviderClient = mock(LlmProviderClient.class);
    private final ProviderAvailabilityService providerAvailability = mock(ProviderAvailabilityService.class);
    private final HealthCheckProperties properties = new HealthCheckProperties();
    private final ModelRecoveryTracker tracker = new ModelRecoveryTracker(
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC), () -> 0.5);
    private final HealthCheckService healthCheckService;

    ModelRegistryDisabledTest() {
        properties.setRecoveryMaxModelsPerSweep(2);
        healthCheckService = new HealthCheckService(properties, modelRegistry, llmProviderClient,
                mock(RoutingService.class), mock(CircuitBreakerService.class), mock(ModelStatusUpdater.class),
                providerAvailability, tracker);
    }

    @AfterEach
    void shutdownExecutor() {
        healthCheckService.shutdown();
    }

    @Test
    void recoverySweepExcludesDisabledModels() {
        Model enabled = model("enabled-model", "provider-a", 10, true);
        Model disabled = model("disabled-model", "provider-b", 100, false); // higher priority

        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("enabled-model", "disabled-model"));
        when(modelRegistry.getModel("enabled-model")).thenReturn(Optional.of(enabled));
        when(modelRegistry.getModel("disabled-model")).thenReturn(Optional.of(disabled));
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        when(modelRegistry.getProviderConfig(anyString())).thenReturn(config);
        when(providerAvailability.isAvailable(anyString())).thenReturn(true);
        tracker.recordFailure("enabled-model");
        tracker.recordFailure("disabled-model");

        List<Model> due = healthCheckService.getDueRecoveryModels(Instant.parse("2025-01-01T00:03:00Z"));

        assertEquals(List.of("enabled-model"), due.stream().map(Model::getId).toList(),
                "Disabled model must be excluded from recovery sweep despite higher priority");
    }

    private Model model(String id, String provider, int priority, boolean enabled) {
        return new Model(id, id, provider, Set.of(Pipeline.CODING), 32_000, priority, Map.of(),
                ModelCapabilities.NONE, enabled);
    }
}
