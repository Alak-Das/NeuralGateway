package com.alak.neuralgateway.service;

import com.alak.neuralgateway.PayloadTelemetryService;
import com.alak.neuralgateway.ToolCallNormalizer;
import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.model.Model.ModelCapabilities;
import com.alak.neuralgateway.domain.routing.RoutingScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying resilience, connection management (SSE-001), provider availability (HEALTH-001, HEALTH-002),
 * and routing score calculation (METRICS-001).
 */
class LlmGatewayFacadeTest {

    private ModelRegistry modelRegistry;
    private RoutingService routingService;
    private HealthCheckService healthCheckService;
    private LlmProviderClient llmProviderClient;
    private ToolCallNormalizer toolCallNormalizer;
    private PayloadTelemetryService payloadTelemetryService;
    private ModelStatusService modelStatusService;
    private SseNotificationService sseNotificationService;
    private RoutingProperties routingProperties;
    private RedisPersistenceService redisPersistenceService;
    private ObjectMapper objectMapper;

    private LlmGatewayFacade facade;

    private Model modelA;
    private Model modelB;

    @BeforeEach
    void setUp() {
        modelRegistry = mock(ModelRegistry.class);
        routingService = mock(RoutingService.class);
        healthCheckService = mock(HealthCheckService.class);
        llmProviderClient = mock(LlmProviderClient.class);
        toolCallNormalizer = mock(ToolCallNormalizer.class);
        payloadTelemetryService = null;
        modelStatusService = mock(ModelStatusService.class);
        sseNotificationService = mock(SseNotificationService.class);
        routingProperties = new RoutingProperties();
        redisPersistenceService = mock(RedisPersistenceService.class);
        objectMapper = new ObjectMapper();

        facade = new LlmGatewayFacade(
                modelRegistry,
                routingService,
                healthCheckService,
                llmProviderClient,
                toolCallNormalizer,
                payloadTelemetryService,
                modelStatusService,
                sseNotificationService,
                routingProperties,
                redisPersistenceService,
                objectMapper,
                mock(TelemetryTraceService.class)
        );

        modelA = new Model("model-a", "Model A", "provider-alpha", Set.of(Pipeline.CODING), 32000, 10, ModelCapabilities.NONE);
        modelB = new Model("model-b", "Model B", "provider-beta", Set.of(Pipeline.CODING), 32000, 5, ModelCapabilities.NONE);
    }

    @Test
    void processChatCompletion_successfulCall_decrementsActiveConnectionsAndRecordsSuccess() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn(null);

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-1");
        response.put("choices", List.of());
        when(llmProviderClient.call(eq("model-a"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hello")));

        Map<String, Object> result = facade.processChatCompletion(request, "test-user", "CODING");

        assertNotNull(result);
        verify(routingService).incrementActiveConnections("model-a");
        verify(routingService).decrementActiveConnections("model-a");
        verify(redisPersistenceService).clearProviderUnavailable("provider-alpha");
        verify(redisPersistenceService).resetProviderConsecutiveErrors("provider-alpha");
    }

    @Test
    void processChatCompletion_providerInCooldown_skipsToNextCandidate() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA, modelB));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn("Rate limited");
        when(redisPersistenceService.getProviderUnavailableReason("provider-beta")).thenReturn(null);

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-2");
        when(llmProviderClient.call(eq("model-b"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hello")));

        Map<String, Object> result = facade.processChatCompletion(request, "test-user", "CODING");

        assertNotNull(result);
        verify(llmProviderClient, never()).call(eq("model-a"), any());
        verify(llmProviderClient, times(1)).call(eq("model-b"), any());
        verify(routingService).incrementActiveConnections("model-b");
        verify(routingService).decrementActiveConnections("model-b");
    }

    @Test
    void processStreamingChatCompletion_guaranteedCleanupViaDoFinallyOnComplete() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn(null);
        when(llmProviderClient.callStream(eq("model-a"), any()))
                .thenReturn(Flux.just("data: {\"model\":\"model-a\"}", "data: [DONE]"));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "stream test")));

        Flux<String> flux = facade.processStreamingChatCompletion(request, "test-user", "CODING");
        List<String> results = flux.collectList().block(Duration.ofSeconds(2));

        assertNotNull(results);
        assertEquals(2, results.size());
        verify(routingService, times(1)).incrementActiveConnections("model-a");
        verify(routingService, times(1)).decrementActiveConnections("model-a");
        verify(redisPersistenceService).clearProviderUnavailable("provider-alpha");
    }

    @Test
    void processStreamingChatCompletion_guaranteedCleanupViaDoFinallyOnCancel() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn(null);
        when(llmProviderClient.callStream(eq("model-a"), any()))
                .thenReturn(Flux.never());

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "cancel test")));

        Flux<String> flux = facade.processStreamingChatCompletion(request, "test-user", "CODING");

        Disposable disposable = flux.subscribe();
        verify(routingService, times(1)).incrementActiveConnections("model-a");

        disposable.dispose();
        verify(routingService, times(1)).decrementActiveConnections("model-a");
    }

    @Test
    void processStreamingChatCompletion_failoverOnErrorReleasesConnection() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA, modelB));
        when(redisPersistenceService.getProviderUnavailableReason(anyString())).thenReturn(null);

        when(llmProviderClient.callStream(eq("model-a"), any()))
                .thenReturn(Flux.error(new LlmProviderClient.UpstreamServiceException("503 Service Unavailable", 503)));
        when(llmProviderClient.callStream(eq("model-b"), any()))
                .thenReturn(Flux.just("data: {\"model\":\"model-b\"}", "data: [DONE]"));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "failover test")));

        Flux<String> flux = facade.processStreamingChatCompletion(request, "test-user", "CODING");
        List<String> results = flux.collectList().block(Duration.ofSeconds(2));

        assertNotNull(results);
        assertEquals(2, results.size());
        verify(routingService, times(1)).incrementActiveConnections("model-a");
        verify(routingService, times(1)).decrementActiveConnections("model-a");
        verify(routingService, times(1)).incrementActiveConnections("model-b");
        verify(routingService, times(1)).decrementActiveConnections("model-b");
    }

    @Test
    void noEligibleProvider_includesCooldownDetailsInErrorMessage() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of());
        when(modelRegistry.getModelsByPipeline(Pipeline.CODING)).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn("Rate limit exceeded (429)");

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "exhaustion test")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                facade.processChatCompletion(request, "user", "CODING"));

        assertTrue(ex.getMessage().contains("exhausted or unavailable"));
        assertTrue(ex.getMessage().contains("provider-alpha"));
        assertTrue(ex.getMessage().contains("Rate limit exceeded (429)"));
    }

    @Test
    void getRoutingScore_computesScoreCorrectly() {
        when(redisPersistenceService.getEmaLatency("model-a", 0.0)).thenReturn(200.0);
        when(routingService.getActiveConnections("model-a")).thenReturn(2);
        when(modelRegistry.getModel("model-a")).thenReturn(Optional.of(modelA));

        routingProperties.setConnectionPenaltyMs(300);

        RoutingScore score = facade.getRoutingScore("model-a");
        assertNotNull(score);
        assertEquals(200.0, score.getEmaLatencyMs());
        assertEquals(2, score.getActiveConnections());
        assertEquals(300.0, score.getConnectionPenaltyPerConnection());
        // calculatedScore = (200 + (2 * 300)) / 10 = 800 / 10 = 80.0
        assertEquals(80.0, score.getCalculatedScore(), 0.001);
    }

    @Test
    void processChatCompletion_formatRejectionFailsOverWithoutProviderCooldown() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA, modelB));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn(null);
        when(redisPersistenceService.getProviderUnavailableReason("provider-beta")).thenReturn(null);

        // modelA rejects format (e.g. multimodal or unsupported parameter)
        when(llmProviderClient.call(eq("model-a"), any()))
                .thenThrow(new IllegalArgumentException("multimodal not supported"));

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-fallback");
        response.put("choices", List.of());
        when(llmProviderClient.call(eq("model-b"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "image payload")));

        Map<String, Object> result = facade.processChatCompletion(request, "test-user", "CODING");

        assertNotNull(result);
        verify(llmProviderClient).call(eq("model-a"), any());
        verify(llmProviderClient).call(eq("model-b"), any());
        // Verify provider-alpha was NOT penalized with consecutive errors
        verify(redisPersistenceService, never()).incrementProviderConsecutiveErrors("provider-alpha");
    }

    @Test
    void processChatCompletion_allProvidersInCooldown_emergencyDegradedModeAttemptsCandidate() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn("Too many consecutive errors");

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-degraded");
        when(llmProviderClient.call(eq("model-a"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "degraded test")));

        Map<String, Object> result = facade.processChatCompletion(request, "cline", "CODING");

        assertNotNull(result);
        assertEquals("chatcmpl-degraded", result.get("id"));
        verify(llmProviderClient).call(eq("model-a"), any());
        verify(redisPersistenceService).clearProviderUnavailable("provider-alpha");
        verify(redisPersistenceService).resetProviderConsecutiveErrors("provider-alpha");
    }

    @Test
    void processStreamingChatCompletion_allProvidersInCooldown_emergencyDegradedModeAttemptsCandidate() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
        when(redisPersistenceService.getProviderUnavailableReason("provider-alpha")).thenReturn("Too many consecutive errors");
        when(llmProviderClient.callStream(eq("model-a"), any()))
                .thenReturn(Flux.just("data: {\"model\":\"model-a\"}", "data: [DONE]"));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "streaming degraded test")));

        Flux<String> flux = facade.processStreamingChatCompletion(request, "cline", "CODING");
        List<String> results = flux.collectList().block(Duration.ofSeconds(2));

        assertNotNull(results);
        assertEquals(2, results.size());
        verify(llmProviderClient).callStream(eq("model-a"), any());
        verify(redisPersistenceService).clearProviderUnavailable("provider-alpha");
        verify(redisPersistenceService).resetProviderConsecutiveErrors("provider-alpha");
    }
}
