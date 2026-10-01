package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleLlmClientTest {
    private final WireMockServer upstream = new WireMockServer(0);

    @AfterEach
    void stopUpstream() {
        if (upstream.isRunning()) upstream.stop();
    }

    @Test
    void retriesUnauthorizedRequestWithAnotherConfiguredKey() {
        upstream.start();
        String modelId = "test/model";
        Model model = new Model(modelId, modelId, "test-provider", Set.of(Model.Pipeline.CODING),
                32_000, 1, Model.ModelCapabilities.NONE);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setBaseUrl(upstream.baseUrl());
        ApiKeyPool keyPool = new ApiKeyPool("test-provider", List.of("rejected-key", "working-key"), 60);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(modelId)).thenReturn(Optional.of(model));
        when(registry.getApiKeyPool("test-provider")).thenReturn(keyPool);
        when(registry.getProviderConfig("test-provider")).thenReturn(config);

        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer rejected-key"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid key\"}")));
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"success\",\"model\":\"test/model\"}")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(WebClient.builder(), registry);
        Map<String, Object> response = client.call(modelId, new java.util.HashMap<>(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello")))));

        assertNotNull(response);
        assertEquals("success", response.get("id"));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer rejected-key")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key")));
    }

    @Test
    void retriesUnauthorizedStreamWithAnotherConfiguredKey() {
        upstream.start();
        String modelId = "test/model";
        Model model = new Model(modelId, modelId, "test-provider", Set.of(Model.Pipeline.CODING),
                32_000, 1, Model.ModelCapabilities.NONE);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setBaseUrl(upstream.baseUrl());
        ApiKeyPool keyPool = new ApiKeyPool("test-provider", List.of("rejected-key", "working-key"), 60);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(modelId)).thenReturn(Optional.of(model));
        when(registry.getApiKeyPool("test-provider")).thenReturn(keyPool);
        when(registry.getProviderConfig("test-provider")).thenReturn(config);

        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer rejected-key"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid key\"}")));
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"id\":\"stream-success\",\"model\":\"test/model\"}\n\ndata: [DONE]\n\n")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(WebClient.builder(), registry);
        List<String> events = client.callStream(modelId, new java.util.HashMap<>(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello")))))
                .collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("[DONE]", events.get(1));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer rejected-key")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key")));
    }

    @Test
    void retriesRateLimitedRequestWithAnotherConfiguredKey() {
        upstream.start();
        String modelId = "test/model";
        Model model = new Model(modelId, modelId, "test-provider", Set.of(Model.Pipeline.CODING),
                32_000, 1, Model.ModelCapabilities.NONE);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setBaseUrl(upstream.baseUrl());
        ApiKeyPool keyPool = new ApiKeyPool("test-provider", List.of("limited-key", "working-key"), 60);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(modelId)).thenReturn(Optional.of(model));
        when(registry.getApiKeyPool("test-provider")).thenReturn(keyPool);
        when(registry.getProviderConfig("test-provider")).thenReturn(config);

        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer limited-key"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"rate limit exceeded\"}}")));
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"success\",\"model\":\"test/model\"}")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(WebClient.builder(), registry);
        Map<String, Object> response = client.call(modelId, new java.util.HashMap<>(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello")))));

        assertNotNull(response);
        assertEquals("success", response.get("id"));
        assertTrue(keyPool.isCoolingDown("limited-key"));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer limited-key")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key")));
    }

    @Test
    void retriesStreamWhenSseEmbedsRateLimitError() {
        upstream.start();
        String modelId = "test/model";
        Model model = new Model(modelId, modelId, "test-provider", Set.of(Model.Pipeline.CODING),
                32_000, 1, Model.ModelCapabilities.NONE);
        LlmProvidersProperties.ProviderConfig config = new LlmProvidersProperties.ProviderConfig();
        config.setBaseUrl(upstream.baseUrl());
        ApiKeyPool keyPool = new ApiKeyPool("test-provider", List.of("limited-key", "working-key"), 60);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(modelId)).thenReturn(Optional.of(model));
        when(registry.getApiKeyPool("test-provider")).thenReturn(keyPool);
        when(registry.getProviderConfig("test-provider")).thenReturn(config);

        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer limited-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"error\":{\"code\":429,\"message\":\"rate limit exceeded\"}}\n\n")));
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"id\":\"stream-success\",\"model\":\"test/model\"}\n\ndata: [DONE]\n\n")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(WebClient.builder(), registry);
        List<String> events = client.callStream(modelId, new java.util.HashMap<>(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello")))))
                .collectList().block();

        assertNotNull(events);
        assertEquals(List.of("{\"id\":\"stream-success\",\"model\":\"test/model\"}", "[DONE]"), events);
        assertTrue(keyPool.isCoolingDown("limited-key"));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer limited-key")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer working-key")));
    }
}
