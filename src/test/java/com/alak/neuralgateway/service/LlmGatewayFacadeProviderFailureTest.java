package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.ToolCallNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayFacadeProviderFailureTest {
    @Mock private ModelRegistry modelRegistry;
    @Mock private RoutingService routingService;
    @Mock private CircuitBreakerService circuitBreakerService;
    @Mock private HealthCheckService healthCheckService;
    @Mock private LlmProviderClient llmProviderClient;
    @Mock private ToolCallNormalizer toolCallNormalizer;
    @Mock private ModelStatusService modelStatusService;
    @Mock private ProviderAvailabilityService providerAvailabilityService;

    private LlmGatewayFacade facade;

    @BeforeEach
    void setUp() {
        facade = new LlmGatewayFacade(modelRegistry, routingService, circuitBreakerService, healthCheckService,
                llmProviderClient, toolCallNormalizer, null, modelStatusService, null, null, null,
                providerAvailabilityService);
        when(circuitBreakerService.isRequestPermitted(any())).thenReturn(true);
    }

    @Test
    void nonStreamingRateLimitFailsOverWithoutMarkingTheModelDown() {
        Model limited = model("limited", "provider-a");
        Model fallback = model("fallback", "provider-b");
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt()))
                .thenReturn(new ArrayList<>(List.of(limited, fallback)));
        when(providerAvailabilityService.isAvailable("provider-a")).thenReturn(true);
        when(providerAvailabilityService.isAvailable("provider-b")).thenReturn(true);

        LlmProviderClient.RateLimitException rateLimit = new LlmProviderClient.RateLimitException(
                "provider rate limit", "provider-a", "api-key", limited.getId());
        when(llmProviderClient.call(eq(limited.getId()), anyMap())).thenThrow(rateLimit);
        Map<String, Object> response = Map.of("id", "success");
        when(llmProviderClient.call(eq(fallback.getId()), anyMap())).thenReturn(response);

        Map<String, Object> result = facade.processChatCompletion(request(), null, "tx", "CODING");

        assertEquals(response, result);
        verify(providerAvailabilityService).recordFailure(any(ProviderFailureException.class));
        verify(circuitBreakerService, never()).recordFailure(eq(limited.getId()), any());
        verify(healthCheckService, never()).recordRoutedFailure(eq(limited.getId()), any());
        verify(modelStatusService, never()).updateStatus(eq(limited.getId()), any(HealthCheckResult.class));
        verify(routingService, times(1)).decrementActiveConnections(limited.getId());
        verify(routingService, times(1)).decrementActiveConnections(fallback.getId());
    }

    @Test
    void nonStreamingPostProcessingFailureStillReleasesTheActiveConnectionOnce() {
        Model model = model("model", "provider-a");
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(new ArrayList<>(List.of(model)));
        when(providerAvailabilityService.isAvailable("provider-a")).thenReturn(true);
        when(llmProviderClient.call(eq(model.getId()), anyMap())).thenReturn(Map.of("id", "success"));
        org.mockito.Mockito.doThrow(new IllegalStateException("post-processing failed"))
                .when(toolCallNormalizer).normalizeToolCalls(anyMap(), anyMap(), eq("tx"));

        assertThrows(RuntimeException.class,
                () -> facade.processChatCompletion(request(), null, "tx", "CODING"));

        verify(routingService, times(1)).incrementActiveConnections(model.getId());
        verify(routingService, times(1)).decrementActiveConnections(model.getId());
    }

    @Test
    void streamingRateLimitSkipsRemainingModelsFromTheUnavailableProvider() {
        Model limited = model("limited", "provider-a");
        Model sameProviderFallback = model("same-provider-fallback", "provider-a");
        Model fallback = model("fallback", "provider-b");
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt()))
                .thenReturn(new ArrayList<>(List.of(limited, sameProviderFallback, fallback)));
        AtomicBoolean providerUnavailable = new AtomicBoolean(false);
        when(providerAvailabilityService.isAvailable("provider-a"))
                .thenAnswer(invocation -> !providerUnavailable.get());
        when(providerAvailabilityService.isAvailable("provider-b")).thenReturn(true);
        doAnswer(invocation -> {
            providerUnavailable.set(true);
            return null;
        }).when(providerAvailabilityService).recordFailure(any(ProviderFailureException.class));

        LlmProviderClient.RateLimitException rateLimit = new LlmProviderClient.RateLimitException(
                "provider rate limit", "provider-a", "api-key", limited.getId());
        when(llmProviderClient.callStream(eq(limited.getId()), anyMap())).thenReturn(Flux.error(rateLimit));
        when(llmProviderClient.callStream(eq(fallback.getId()), anyMap())).thenReturn(Flux.just("fallback chunk"));

        List<String> result = facade.processStreamingChatCompletion(request(), null, "tx", "CODING").collectList().block();

        assertEquals(List.of("fallback chunk"), result);
        verify(llmProviderClient, never()).callStream(eq(sameProviderFallback.getId()), anyMap());
        verify(providerAvailabilityService).recordFailure(any(ProviderFailureException.class));
        verify(circuitBreakerService, never()).recordFailure(eq(limited.getId()), any());
        verify(healthCheckService, never()).recordRoutedFailure(eq(limited.getId()), any());
        verify(modelStatusService, never()).updateStatus(eq(limited.getId()), any(HealthCheckResult.class));
    }

    private Model model(String id, String provider) {
        return new Model(id, id, provider, Set.of(Pipeline.CODING), 32_000, 1,
                Model.ModelCapabilities.NONE);
    }

    private Map<String, Object> request() {
        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hello")));
        request.put("max_tokens", 1);
        return request;
    }
}
