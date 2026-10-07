package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.ModelCapabilities;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiCompatibleLlmClientTest {

    private WireMockServer wireMockServer;
    private ModelRegistry modelRegistry;
    private OpenAiCompatibleLlmClient client;
    private Model testModel;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        modelRegistry = mock(ModelRegistry.class);
        testModel = new Model("test-model", "Test Model", "test-provider", Set.of(Pipeline.CODING), 8192, 10, ModelCapabilities.NONE);
        when(modelRegistry.getModel("test-model")).thenReturn(Optional.of(testModel));

        LlmProvidersProperties.ProviderConfig providerConfig = new LlmProvidersProperties.ProviderConfig();
        providerConfig.setBaseUrl(wireMockServer.baseUrl());
        when(modelRegistry.getProviderConfig("test-provider")).thenReturn(providerConfig);

        ApiKeyPool apiKeyPool = new ApiKeyPool("test-provider", List.of("test-key-1"), 0);
        when(modelRegistry.getApiKeyPool("test-provider")).thenReturn(apiKeyPool);

        WebClient.Builder webClientBuilder = WebClient.builder();
        client = new OpenAiCompatibleLlmClient(webClientBuilder, modelRegistry);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    void call_prematureCloseOnFirstAttempt_retriesAndSucceeds() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("Retry Premature Close")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("Retried")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("Retry Premature Close")
                .whenScenarioStateIs("Retried")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"chatcmpl-ok\",\"choices\":[]}")));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Map<String, Object> response = client.call("test-model", request);

        assertNotNull(response);
        assertEquals("chatcmpl-ok", response.get("id"));
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void callStream_prematureCloseBeforeData_retriesAndStreamsSuccessfully() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("Retry Premature Close Stream")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("Stream Retried")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("Retry Premature Close Stream")
                .whenScenarioStateIs("Stream Retried")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n")));

        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Flux<String> stream = client.callStream("test-model", request);
        List<String> chunks = stream.collectList().block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        assertTrue(chunks.size() >= 1);
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }
}
