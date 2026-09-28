package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with any OpenAI-compatible API using the ModelRegistry's dynamic configurations.
 */
@Service
public class NvidiaLlmClient implements LlmProviderClient {

    private final WebClient webClient;
    private final ModelRegistry modelRegistry;

    public NvidiaLlmClient(WebClient.Builder webClientBuilder, ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;

        ConnectionProvider connectionProvider = ConnectionProvider.builder("llm-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(15))
                .maxLifeTime(Duration.ofMinutes(1))
                .evictInBackground(Duration.ofSeconds(10))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofSeconds(120));

        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private String getApiKeyForModel(Model model) {
        ApiKeyPool pool = modelRegistry.getApiKeyPool(model.getProviderId());
        if (pool == null) return "";
        return pool.getAvailableKey();
    }

    private String getBaseUrlForModel(Model model) {
        LlmProvidersProperties.ProviderConfig config = modelRegistry.getProviderConfig(model.getProviderId());
        if (config == null || config.getBaseUrl() == null) {
            return "https://api.openai.com/v1";
        }
        return config.getBaseUrl();
    }

    @Override
    public Map<String, Object> call(String modelId, Map<String, Object> request) {
        Model model = modelRegistry.getModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));
        String apiKey = getApiKeyForModel(model);
        String baseUrl = getBaseUrlForModel(model);
        ApiKeyPool pool = modelRegistry.getApiKeyPool(model.getProviderId());

        request.put("model", modelId);
        request.put("stream", false);
        request.remove("stream_options");

        if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyActive(apiKey);
        try {
            return webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw mapWebClientException(e, model.getProviderId(), apiKey, modelId);
        } catch (org.springframework.web.reactive.function.client.WebClientRequestException e) {
            Throwable cause = e.getCause();
            if (cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
                throw new UpstreamServiceException("Upstream read timed out for model: " + modelId, 504);
            }
            if (cause instanceof reactor.netty.http.client.PrematureCloseException) {
                throw new UpstreamServiceException("Upstream connection prematurely closed for model: " + modelId, 503);
            }
            String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : "Network communication error";
            throw new UpstreamServiceException("Upstream connection error for model " + modelId + ": " + msg, 503);
        } catch (UpstreamServiceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.getClass().getSimpleName();
            throw new UpstreamServiceException("Upstream error for model " + modelId + ": " + msg, 500);
        } finally {
            if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyIdle(apiKey);
        }
    }

    @Override
    public List<Map<String, Object>> callStream(String modelId, Map<String, Object> request) {
        Map<String, Object> resp = call(modelId, request);
        return List.of(resp);
    }

    private RuntimeException mapWebClientException(WebClientResponseException e, String providerId, String apiKey, String modelId) {
        int status = e.getStatusCode().value();
        String responseBody = e.getResponseBodyAsString();

        boolean isRateLimit = (status == 429) || 
                              (status == 503 && responseBody != null && (responseBody.contains("ResourceExhausted") || responseBody.contains("overloaded")));

        if (isRateLimit) {
            // Cool down the specific key if apiKeyPool is available
            ApiKeyPool pool = modelRegistry.getApiKeyPool(providerId);
            if (pool != null && apiKey != null && !apiKey.isEmpty()) {
                pool.recordRateLimit(apiKey, Duration.ofSeconds(30));
            }
            return new RateLimitException(responseBody, providerId, apiKey, modelId);
        }

        if (status == 410) {
            // End of life model (Gone). Force failover instead of hard crash.
            return new UpstreamServiceException("Model deprecated (410): " + responseBody, status);
        }

        if (status >= 400 && status < 500) {
            return new IllegalArgumentException(responseBody); // 400 Bad Request, 401, 403, 404, 422
        } else {
            return new UpstreamServiceException(responseBody, status);
        }
    }
}