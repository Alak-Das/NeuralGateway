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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the smarter health-check sweep selection: the main sweep probes
 * ALL models of a due provider (circuit-blocked first) instead of 1 per provider,
 * and the recovery sweep picks up stale circuit-blocked models that have no
 * recovery state while still honoring backoff for models that do.
 */
class HealthCheckSweepSelectionTest {

    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final LlmProviderClient llmProviderClient = mock(LlmProviderClient.class);
    private final RoutingService routingService = mock(RoutingService.class);
    private final CircuitBreakerService circuitBreakerService = mock(CircuitBreakerService.class);
    private final ModelStatusUpdater modelStatusUpdater = mock(ModelStatusUpdater.class);
    private final ProviderAvailabilityService providerAvailability = mock(ProviderAvailabilityService.class);
    private final HealthCheckProperties properties = new HealthCheckProperties();
    private final ModelRecoveryTracker tracker = new ModelRecoveryTracker(
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC), () -> 0.5);
    private final HealthCheckService healthCheckService;

    HealthCheckSweepSelectionTest() {
        healthCheckService = new HealthCheckService(properties, modelRegistry, llmProviderClient,
                routingService, circuitBreakerService, modelStatusUpdater, providerAvailability, tracker);
    }

    @AfterEach
    void shutdown() {
        healthCheckService.shutdown();
    }

    private void stubProvider(String providerId, boolean available) {
        when(modelRegistry.getProviderConfig(providerId)).thenReturn(new LlmProvidersProperties.ProviderConfig());
        when(providerAvailability.isAvailable(providerId)).thenReturn(available);
    }

    private void stubModel(Model model) {
        when(modelRegistry.getModel(model.getId())).thenReturn(Optional.of(model));
    }

    @Test
    void mainSweepSelectsAllModelsOfDueProviderInsteadOfOne() {
        Model first = model("first", "nvidia", 5);
        Model second = model("second", "nvidia", 3);
        stubProvider("nvidia", true);
        when(circuitBreakerService.isCircuitOpen(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        List<Model> selected = healthCheckService.selectModelsToProbe(
                List.of(first, second), Instant.parse("2025-01-01T00:00:00Z"));

        // The old round-robin would have picked only ONE of the two NVIDIA models.
        assertEquals(List.of("first", "second"), selected.stream().map(Model::getId).toList());
    }

    @Test
    void mainSweepProbesCircuitBlockedModelsFirst() {
        Model blocked = model("blocked", "nvidia", 1);
        Model healthy = model("healthy", "nvidia", 9);
        stubProvider("nvidia", true);
        when(circuitBreakerService.isCircuitOpen("blocked")).thenReturn(true);
        when(circuitBreakerService.isCircuitOpen("healthy")).thenReturn(false);

        List<Model> selected = healthCheckService.selectModelsToProbe(
                List.of(blocked, healthy), Instant.parse("2025-01-01T00:00:00Z"));

        // Blocked model is probed first even though the healthy one has higher priority.
        assertEquals(List.of("blocked", "healthy"), selected.stream().map(Model::getId).toList());
    }

    @Test
    void mainSweepSkipsProviderWithoutHealthCheckConfig() {
        Model orphan = model("orphan", "unknown-provider", 5);
        when(modelRegistry.getProviderConfig("unknown-provider")).thenReturn(null);

        List<Model> selected = healthCheckService.selectModelsToProbe(
                List.of(orphan), Instant.parse("2025-01-01T00:00:00Z"));

        assertTrue(selected.isEmpty());
    }

    @Test
    void recoveryIncludesStaleBlockedModelWithoutRecoveryStateFirst() {
        Model staleBlocked = model("stale-blocked", "provider-one", 1);
        Model dueRecovery = model("due-recovery", "provider-two", 10);
        stubProvider("provider-one", true);
        stubProvider("provider-two", true);
        stubModel(staleBlocked);
        stubModel(dueRecovery);
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("stale-blocked", "due-recovery"));
        when(circuitBreakerService.isCircuitOpen("stale-blocked")).thenReturn(true);
        when(circuitBreakerService.isCircuitOpen("due-recovery")).thenReturn(false);
        // due-recovery has an elapsed recovery backoff; stale-blocked has none but an OPEN circuit.
        tracker.recordFailure("due-recovery");

        List<Model> due = healthCheckService.getDueRecoveryModels(Instant.parse("2025-01-01T00:03:00Z"));

        // Stale blocked model is picked up on the fast recovery cadence and ranked first.
        assertEquals(List.of("stale-blocked", "due-recovery"), due.stream().map(Model::getId).toList());
    }

    @Test
    void recoveryRespectsBackoffForBlockedModelWithRecoveryState() {
        Model blockedInBackoff = model("blocked-in-backoff", "provider-one", 1);
        stubProvider("provider-one", true);
        stubModel(blockedInBackoff);
        when(modelRegistry.getAllModelIds()).thenReturn(Set.of("blocked-in-backoff"));
        when(circuitBreakerService.isCircuitOpen("blocked-in-backoff")).thenReturn(true);
        tracker.recordFailure("blocked-in-backoff");

        // 3s after the failure: backoff (30s) has NOT elapsed, so even though the
        // circuit is OPEN the model must not be probed on the fast recovery cadence.
        List<Model> due = healthCheckService.getDueRecoveryModels(Instant.parse("2025-01-01T00:00:03Z"));

        assertTrue(due.isEmpty());
    }

    private Model model(String id, String provider, int priority) {
        return new Model(id, id, provider, Set.of(Pipeline.CODING), 32_000, priority,
                Model.ModelCapabilities.NONE);
    }
}
