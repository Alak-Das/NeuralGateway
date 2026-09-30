package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.HealthCheckProperties;
import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthCheckRecoveryTest {
    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final LlmProviderClient llmProviderClient = mock(LlmProviderClient.class);
    private final ProviderAvailabilityService providerAvailability = mock(ProviderAvailabilityService.class);
    private final HealthCheckProperties properties = new HealthCheckProperties();
    private final ModelRecoveryTracker tracker = new ModelRecoveryTracker(
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC), () -> 0.5);
    private final HealthCheckService healthCheckService;

    HealthCheckRecoveryTest() {
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
    void dueModelsAreOrderedByPriorityLimitedAndFilteredByCooldown() {
        Model high = model("high", "provider-one", 10);
        Model medium = model("medium", "provider-two", 5);
        Model cooled = model("cooled", "cooldown", 100);
        Model low = model("low", "provider-three", 1);
        List<Model> models = List.of(high, medium, cooled, low);
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("high", "medium", "cooled", "low"));
        for (Model model : models) {
            when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
            LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
            when(modelRegistry.getProviderConfig(model.getProviderId())).thenReturn(config);
            tracker.recordFailure(model.getId());
            when(providerAvailability.isAvailable(model.getProviderId())).thenReturn(true);
        }
        when(providerAvailability.isAvailable("cooldown")).thenReturn(false);

        List<Model> due = healthCheckService.getDueRecoveryModels(Instant.parse("2025-01-01T00:03:00Z"));

        assertEquals(List.of("high", "medium"), due.stream().map(Model::getId).toList());
    }

    @Test
    void recoveryModelsRespectPerSweepLimitEvenWhenMoreAreDue() {
        Model first = model("first", "provider-one", 9);
        Model second = model("second", "provider-two", 8);
        Model third = model("third", "provider-three", 7);
        List<Model> models = List.of(first, second, third);
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("first", "second", "third"));
        for (Model model : models) {
            when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
            when(modelRegistry.getProviderConfig(model.getProviderId())).thenReturn(new LlmProvidersProperties.ProviderConfig());
            tracker.recordFailure(model.getId());
            when(providerAvailability.isAvailable(model.getProviderId())).thenReturn(true);
        }

        List<Model> due = healthCheckService.getDueRecoveryModels(Instant.parse("2025-01-01T00:03:00Z"));

        assertEquals(List.of("first", "second"), due.stream().map(Model::getId).toList());
    }

    @Test
    void recoverySweepSelectsAtMostOneModelPerProviderAndPacesNextProbe() {
        Model first = model("first", "shared-provider", 9);
        Model second = model("second", "shared-provider", 8);
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("first", "second"));
        for (Model model : List.of(first, second)) {
            when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
            when(modelRegistry.getProviderConfig(model.getProviderId()))
                    .thenReturn(new LlmProvidersProperties.ProviderConfig());
            tracker.recordFailure(model.getId());
        }
        when(providerAvailability.isAvailable("shared-provider")).thenReturn(true);

        Instant now = Instant.parse("2025-01-01T00:03:00Z");
        List<Model> selected = healthCheckService.getDueRecoveryModels(now);

        assertEquals(List.of("first"), selected.stream().map(Model::getId).toList());
        assertEquals(List.of(), healthCheckService.getDueRecoveryModels(now.plusSeconds(4)));
        assertEquals(List.of("first"), healthCheckService.getDueRecoveryModels(now.plusSeconds(5))
                .stream().map(Model::getId).toList());
    }

    @Test
    void pingDoesNotCallAProviderAlreadyInCooldown() {
        Model model = model("cooled", "provider", 1);
        when(modelRegistry.getModel("cooled")).thenReturn(Optional.of(model));
        when(providerAvailability.isAvailable("provider")).thenReturn(false);

        healthCheckService.pingModel("cooled");

        verify(llmProviderClient, never()).call(org.mockito.ArgumentMatchers.eq("cooled"),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void pingRequiresAnExactProviderReportedModelId() {
        Model model = model("requested-model", "provider", 1);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(providerAvailability.isAvailable("provider")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of("model", "other-model"));

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertFalse(result.isUp());
        org.junit.jupiter.api.Assertions.assertTrue(result.getErrorMessage().contains("model mismatch"));
    }

    @Test
    void pingRejectsResponseWithoutAProviderReportedModelId() {
        Model model = model("requested-model", "provider", 1);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(providerAvailability.isAvailable("provider")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of());

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertFalse(result.isUp());
        org.junit.jupiter.api.Assertions.assertTrue(result.getErrorMessage().contains("model mismatch"));
    }

    @Test
    void successfulPingValidatesResponseModelAndClearsRecoveryState() {
        Model model = model("requested-model", "provider", 1);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(providerAvailability.isAvailable("provider")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of("model", model.getId()));
        tracker.recordFailure(model.getId());

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertTrue(result.isUp());
        verify(llmProviderClient, times(1)).call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap());
        org.junit.jupiter.api.Assertions.assertFalse(tracker.hasState(model.getId()));
    }

    @Test
    void pingAcceptsProviderQualifiedModelIdWhenProviderAllowsIt() {
        Model model = model("gpt-oss-120b", "antseed", 1);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setAllowQualifiedModelIds(true);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(modelRegistry.getProviderConfig("antseed")).thenReturn(config);
        when(providerAvailability.isAvailable("antseed")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Map.of("model", "openai/gpt-oss-120b"));

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertTrue(result.isUp());
    }

    @Test
    void pingRejectsProviderQualifiedModelIdWhenProviderDoesNotAllowIt() {
        Model model = model("gpt-oss-120b", "antseed", 1);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(modelRegistry.getProviderConfig("antseed")).thenReturn(config);
        when(providerAvailability.isAvailable("antseed")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Map.of("model", "openai/gpt-oss-120b"));

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertFalse(result.isUp());
        org.junit.jupiter.api.Assertions.assertTrue(result.getErrorMessage().contains("model mismatch"));
    }

    @Test
    void pingAcceptsExactCanonicalConfigModelId() {
        Model model = model("Qwen/Qwen3-235B-A22B-Instruct-2507", "antseed", 1);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setAllowQualifiedModelIds(true);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(modelRegistry.getProviderConfig("antseed")).thenReturn(config);
        when(providerAvailability.isAvailable("antseed")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Map.of("model", "Qwen/Qwen3-235B-A22B-Instruct-2507"));

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertTrue(result.isUp());
    }

    @Test
    void pingStillRejectsMissingModelFieldWhenProviderAllowsQualifiedIds() {
        Model model = model("openai/gpt-oss-120b", "antseed", 1);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setAllowQualifiedModelIds(true);
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
        when(modelRegistry.getProviderConfig("antseed")).thenReturn(config);
        when(providerAvailability.isAvailable("antseed")).thenReturn(true);
        when(llmProviderClient.call(org.mockito.ArgumentMatchers.eq(model.getId()),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of());

        var result = healthCheckService.pingModel(model.getId());

        org.junit.jupiter.api.Assertions.assertFalse(result.isUp());
        org.junit.jupiter.api.Assertions.assertTrue(result.getErrorMessage().contains("model mismatch"));
    }

    private Model model(String id, String provider, int priority) {
        return new Model(id, id, provider, Set.of(Pipeline.CODING), 32_000, priority,
                Model.ModelCapabilities.NONE);
    }
}