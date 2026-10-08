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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying resilience, connection management (SSE-001), model-level failover,
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
    }

    @Test
    void processChatCompletion_failoverToNextHighestModel_whenFirstModelFails() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA, modelB));

        when(llmProviderClient.call(eq("model-a"), any()))
                .thenThrow(new LlmProviderClient.UpstreamServiceException("503 Service Unavailable", 503));

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-2");
        when(llmProviderClient.call(eq("model-b"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hello")));

        Map<String, Object> result = facade.processChatCompletion(request, "test-user", "CODING");

        assertNotNull(result);
        assertEquals("chatcmpl-2", result.get("id"));
        verify(llmProviderClient, times(1)).call(eq("model-a"), any());
        verify(llmProviderClient, times(1)).call(eq("model-b"), any());
        verify(routingService).decrementActiveConnections("model-a");
        verify(routingService).decrementActiveConnections("model-b");
    }

    @Test
    void processChatCompletion_failoverToNextHighestModel_sameProvider() {
        // Both models belong to the same provider (e.g. nvidia)
        Model nvidia1 = new Model("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron Ultra", "nvidia", Set.of(Pipeline.CODING), 32000, 100, ModelCapabilities.NONE);
        Model nvidia2 = new Model("z-ai/glm-5.3", "GLM 5.3", "nvidia", Set.of(Pipeline.CODING), 32000, 90, ModelCapabilities.NONE);

        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(nvidia1, nvidia2));

        when(llmProviderClient.call(eq("nvidia/nemotron-3-ultra-550b-a55b"), any()))
                .thenThrow(new LlmProviderClient.UpstreamServiceException("503 Overloaded", 503));

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-nvidia2");
        when(llmProviderClient.call(eq("z-ai/glm-5.3"), any())).thenReturn(response);

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hello")));

        Map<String, Object> result = facade.processChatCompletion(request, "cline", "CODING");

        assertNotNull(result);
        assertEquals("chatcmpl-nvidia2", result.get("id"));
        // First model failed, but gateway moved directly to next highest model on the same provider
        verify(llmProviderClient, times(1)).call(eq("nvidia/nemotron-3-ultra-550b-a55b"), any());
        verify(llmProviderClient, times(1)).call(eq("z-ai/glm-5.3"), any());
    }

    @Test
    void processStreamingChatCompletion_guaranteedCleanupViaDoFinallyOnComplete() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
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
    }

    @Test
    void processStreamingChatCompletion_guaranteedCleanupViaDoFinallyOnCancel() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA));
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
    void processStreamingChatCompletion_failoverToNextHighestModel_sameProvider() {
        Model nvidia1 = new Model("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron Ultra", "nvidia", Set.of(Pipeline.CODING), 32000, 100, ModelCapabilities.NONE);
        Model nvidia2 = new Model("z-ai/glm-5.3", "GLM 5.3", "nvidia", Set.of(Pipeline.CODING), 32000, 90, ModelCapabilities.NONE);

        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(nvidia1, nvidia2));

        when(llmProviderClient.callStream(eq("nvidia/nemotron-3-ultra-550b-a55b"), any()))
                .thenReturn(Flux.error(new LlmProviderClient.UpstreamServiceException("503 Overloaded", 503)));
        when(llmProviderClient.callStream(eq("z-ai/glm-5.3"), any()))
                .thenReturn(Flux.just("data: {\"model\":\"z-ai/glm-5.3\"}", "data: [DONE]"));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "stream failover test")));

        Flux<String> flux = facade.processStreamingChatCompletion(request, "cline", "CODING");
        List<String> results = flux.collectList().block(Duration.ofSeconds(2));

        assertNotNull(results);
        assertEquals(2, results.size());
        verify(llmProviderClient).callStream(eq("nvidia/nemotron-3-ultra-550b-a55b"), any());
        verify(llmProviderClient).callStream(eq("z-ai/glm-5.3"), any());
    }

    @Test
    void noEligibleProvider_exhaustion_throwsIllegalStateException() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of());

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "exhaustion test")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                facade.processChatCompletion(request, "user", "CODING"));

        assertTrue(ex.getMessage().contains("exhausted or unavailable"));
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
    void processChatCompletion_formatRejectionFailsOverToNextCandidate() {
        when(routingService.selectModels(eq(Pipeline.CODING), anyInt())).thenReturn(List.of(modelA, modelB));

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
    }
}
