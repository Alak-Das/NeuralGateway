package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.model.Model;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with any OpenAI-compatible API using the ModelRegistry's dynamic configurations.
 */
@Service
public class OpenAiCompatibleLlmClient implements LlmProviderClient {

    private final WebClient webClient;
    private final ModelRegistry modelRegistry;

    public OpenAiCompatibleLlmClient(WebClient.Builder webClientBuilder, ModelRegistry modelRegistry) {
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
            Map<String, Object> response = webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyHealthy(apiKey);
            return response;
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
    public Flux<String> callStream(String modelId, Map<String, Object> request) {
        Model model = modelRegistry.getModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));

        return Flux.defer(() -> {
            String apiKey = getApiKeyForModel(model);
            String baseUrl = getBaseUrlForModel(model);
            ApiKeyPool pool = modelRegistry.getApiKeyPool(model.getProviderId());

            request.put("model", modelId);
            request.put("stream", true);

            if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyActive(apiKey);

            return webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .mapNotNull(ServerSentEvent::data)
                    .doOnNext(data -> {
                        // Detect upstream errors embedded in SSE data events (e.g. NVIDIA 503 in-stream).
                        // Providers may return 200 OK with an SSE body containing {"error":{...}} instead
                        // of using the HTTP status code. Without this check the error silently passes through
                        // as normal data, bypassing the failover logic entirely.
                        if (data.contains("\"error\"") && data.contains("\"code\"")) {
                            try {
                                Map parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(data, Map.class);
                                if (parsed.containsKey("error")) {
                                    Object errorObj = parsed.get("error");
                                    if (errorObj instanceof Map errorMap) {
                                        int code = errorMap.get("code") instanceof Number n ? n.intValue()
                                                : errorMap.get("code") instanceof String s ? Integer.parseInt(s) : 500;
                                        String msg = errorMap.get("message") instanceof String s ? s : data;
                                        throw new UpstreamServiceException(msg, code);
                                    }
                                }
                            } catch (UpstreamServiceException e) {
                                throw e;
                            } catch (Exception ignored) {
                                // Not a parseable error — pass through as normal data
                            }
                        }
                    })
                    .onErrorMap(WebClientResponseException.class,
                            e -> mapWebClientException(e, model.getProviderId(), apiKey, modelId))
                    .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class,
                            e -> mapWebClientRequestException(e, modelId))
                    .doOnComplete(() -> {
                        if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyHealthy(apiKey);
                    })
                    .doFinally(signal -> {
                        if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyIdle(apiKey);
                    });
        });
    }

    private RuntimeException mapWebClientRequestException(
            org.springframework.web.reactive.function.client.WebClientRequestException e, String modelId) {
        Throwable cause = e.getCause();
        if (cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
            return new UpstreamServiceException("Upstream read timed out for model: " + modelId, 504);
        }
        if (cause instanceof reactor.netty.http.client.PrematureCloseException) {
            return new UpstreamServiceException("Upstream connection prematurely closed for model: " + modelId, 503);
        }
        String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : "Network communication error";
        return new UpstreamServiceException("Upstream connection error for model " + modelId + ": " + msg, 503);
    }

    private RuntimeException mapWebClientException(WebClientResponseException e, String providerId, String apiKey, String modelId) {
        int status = e.getStatusCode().value();
        String responseBody = e.getResponseBodyAsString();
        Duration retryAfter = parseRetryAfter(e);
        String lowerBody = responseBody == null ? "" : responseBody.toLowerCase(java.util.Locale.ROOT);

        boolean quotaExhausted = lowerBody.contains("quota") || lowerBody.contains("api calls / month")
                || lowerBody.contains("monthly limit") || lowerBody.contains("billing") && lowerBody.contains("limit");
        boolean providerOverloaded = status == 503 && (lowerBody.contains("resourceexhausted")
                || lowerBody.contains("at capacity") || lowerBody.contains("no capacity"));

        if (quotaExhausted || status == 429 || providerOverloaded) {
            ApiKeyPool pool = modelRegistry.getApiKeyPool(providerId);
            if (pool != null && apiKey != null && !apiKey.isEmpty()) {
                pool.recordRateLimit(apiKey, retryAfter != null ? retryAfter : Duration.ofSeconds(30));
            }
            ProviderFailureType type = quotaExhausted ? ProviderFailureType.QUOTA_EXHAUSTED
                    : providerOverloaded ? ProviderFailureType.PROVIDER_OVERLOAD : ProviderFailureType.RATE_LIMIT;
            return new ProviderFailureException(responseBody, status, providerId, type, retryAfter);
        }

        if (status == 410) {
            return new ProviderFailureException("Model deprecated (410): " + responseBody, status, providerId,
                    ProviderFailureType.MODEL_UNAVAILABLE, null);
        }

        if (status == 401 || status == 403 || status == 404) {
            ProviderFailureType type = status == 404 ? ProviderFailureType.MODEL_UNAVAILABLE : ProviderFailureType.AUTHENTICATION;
            return new ProviderFailureException("Upstream provider error (" + status + "): " + responseBody, status,
                    providerId, type, null);
        }

        if (status >= 400 && status < 500) {
            return new IllegalArgumentException(responseBody); // 400 Bad Request, 422
        } else {
            return new ProviderFailureException(responseBody, status, providerId,
                    ProviderFailureType.TRANSIENT_UPSTREAM, null);
        }
    }

    private Duration parseRetryAfter(WebClientResponseException e) {
        String value = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            try {
                return Duration.between(Instant.now(), java.time.ZonedDateTime.parse(value).toInstant());
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }
}
