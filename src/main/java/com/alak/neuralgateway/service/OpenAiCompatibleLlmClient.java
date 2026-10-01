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
import java.util.Map;

/**
 * Client for interacting with any OpenAI-compatible API using the ModelRegistry's dynamic configurations.
 */
@Service
public class OpenAiCompatibleLlmClient implements LlmProviderClient {

    private final WebClient webClient;
    private final ModelRegistry modelRegistry;

    private static final com.fasterxml.jackson.databind.ObjectMapper UPSTREAM_ERROR_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
        String baseUrl = getBaseUrlForModel(model);
        ApiKeyPool pool = modelRegistry.getApiKeyPool(model.getProviderId());
        int attemptsRemaining = pool == null ? 1 : Math.max(1, pool.getConfiguredKeyCount());

        request.put("model", modelId);
        request.put("stream", false);
        request.remove("stream_options");

        ProviderFailureException lastAuthenticationFailure = null;
        for (int attempt = 0; attempt < attemptsRemaining; attempt++) {
            String apiKey = getApiKeyForModel(model);
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
                RuntimeException mapped = mapWebClientException(e, model.getProviderId(), apiKey, modelId);
                if (mapped instanceof ProviderFailureException failure && pool != null) {
                    if (failure.getFailureType() == ProviderFailureType.AUTHENTICATION) {
                        lastAuthenticationFailure = failure;
                        continue;
                    }
                    // A 429 normally applies to this key. Let ApiKeyPool exclude it and try
                    // another configured key before exposing a provider-wide rate limit.
                    if (failure.getFailureType() == ProviderFailureType.RATE_LIMIT
                            && attempt + 1 < attemptsRemaining) {
                        continue;
                    }
                }
                throw mapped;
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
            } catch (UpstreamServiceException | IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.getClass().getSimpleName();
                throw new UpstreamServiceException("Upstream error for model " + modelId + ": " + msg, 500);
            } finally {
                if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyIdle(apiKey);
            }
        }
        throw lastAuthenticationFailure;
    }

    @Override
    public Flux<String> callStream(String modelId, Map<String, Object> request) {
        Model model = modelRegistry.getModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));

        return Flux.defer(() -> {
            ApiKeyPool pool = modelRegistry.getApiKeyPool(model.getProviderId());
            int attemptsRemaining = pool == null ? 1 : Math.max(1, pool.getConfiguredKeyCount());
            return callStreamWithKey(model, modelId, request, pool, getBaseUrlForModel(model), attemptsRemaining);
        });
    }

    private Flux<String> callStreamWithKey(Model model, String modelId, Map<String, Object> request,
                                           ApiKeyPool pool, String baseUrl, int attemptsRemaining) {
        return Flux.defer(() -> {
            String apiKey = getApiKeyForModel(model);
            java.util.concurrent.atomic.AtomicBoolean emittedData = new java.util.concurrent.atomic.AtomicBoolean();
            Map<String, Object> upstreamRequest = new java.util.HashMap<>(request);
            upstreamRequest.put("model", modelId);
            upstreamRequest.put("stream", true);
            if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyActive(apiKey);

            return webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(upstreamRequest)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .mapNotNull(ServerSentEvent::data)
                    .doOnNext(data -> {
                        RuntimeException embeddedFailure = mapEmbeddedStreamError(
                                data, model.getProviderId(), apiKey, modelId);
                        if (embeddedFailure != null) throw embeddedFailure;
                        emittedData.set(true);
                    })
                    .onErrorMap(WebClientResponseException.class,
                            e -> mapWebClientException(e, model.getProviderId(), apiKey, modelId))
                    .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class,
                            e -> mapWebClientRequestException(e, modelId))
                    .doOnComplete(() -> {
                        if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyHealthy(apiKey);
                    })
                    .onErrorResume(ProviderFailureException.class, failure -> {
                        boolean retryAnotherKey = failure.getFailureType() == ProviderFailureType.AUTHENTICATION
                                || failure.getFailureType() == ProviderFailureType.RATE_LIMIT;
                        if (!retryAnotherKey || pool == null || emittedData.get()) {
                            return Flux.error(failure);
                        }
                        if (failure.getFailureType() == ProviderFailureType.AUTHENTICATION) {
                            pool.markKeyAuthenticationFailure(apiKey);
                        }
                        if (attemptsRemaining <= 1) return Flux.error(failure);
                        return callStreamWithKey(model, modelId, request, pool, baseUrl, attemptsRemaining - 1);
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
        return mapUpstreamError(status, responseBody, parseRetryAfter(e), providerId, apiKey, modelId);
    }

    private RuntimeException mapEmbeddedStreamError(String responseBody, String providerId, String apiKey, String modelId) {
        Map<?, ?> error;
        try {
            Object parsed = UPSTREAM_ERROR_MAPPER.readValue(responseBody, Object.class);
            if (!(parsed instanceof Map<?, ?> map) || !(map.get("error") instanceof Map<?, ?> parsedError)) {
                return null;
            }
            error = parsedError;
        } catch (Exception ignored) {
            // Ordinary completion chunks are not JSON error frames.
            return null;
        }

        Object code = error.get("code");
        int status = parseHttpStatus(code);
        if (status < 0) {
            String upstreamCode = extractUpstreamErrorCode(responseBody);
            String lowerBody = responseBody.toLowerCase(java.util.Locale.ROOT);
            if ((upstreamCode != null && (upstreamCode.contains("invalid_api_key")
                    || upstreamCode.contains("authentication_error") || upstreamCode.contains("unauthorized")))
                    || lowerBody.contains("invalid api key") || lowerBody.contains("unauthorized")) {
                status = 401;
            } else if ((upstreamCode != null && upstreamCode.contains("forbidden"))
                    || lowerBody.contains("forbidden")) {
                status = 403;
            } else if (upstreamCode != null && (upstreamCode.contains("model_not_found")
                    || upstreamCode.contains("model_unavailable"))) {
                status = 404;
            } else if (isQuotaCode(upstreamCode) || isQuotaPhrase(lowerBody)
                    || (upstreamCode != null && (upstreamCode.contains("rate_limit")
                    || upstreamCode.contains("too_many_requests")))) {
                status = 429;
            } else if (lowerBody.contains("resourceexhausted") || lowerBody.contains("at capacity")
                    || lowerBody.contains("no capacity") || lowerBody.contains("overload")
                    || (upstreamCode != null && (upstreamCode.contains("server_error")
                    || upstreamCode.contains("service_unavailable")))) {
                status = 503;
            } else {
                // SSE error frames often omit an HTTP status. Keep them in the structured
                // upstream failure path rather than passing the error frame to the caller.
                status = 500;
            }
        }
        return mapUpstreamError(status, responseBody, null, providerId, apiKey, modelId);
    }

    private static int parseHttpStatus(Object code) {
        if (code instanceof Number number) {
            int status = number.intValue();
            return status >= 400 && status <= 599 ? status : -1;
        }
        if (code instanceof String text) {
            try {
                int status = Integer.parseInt(text);
                return status >= 400 && status <= 599 ? status : -1;
            } catch (NumberFormatException ignored) {
                // Provider error codes are generally symbolic, not HTTP statuses.
            }
        }
        return -1;
    }

    private RuntimeException mapUpstreamError(int status, String responseBody, Duration retryAfter,
                                              String providerId, String apiKey, String modelId) {
        String lowerBody = responseBody == null ? "" : responseBody.toLowerCase(java.util.Locale.ROOT);
        String upstreamCode = extractUpstreamErrorCode(responseBody);

        // A 4xx parameter/validation rejection (e.g. max_tokens below the provider minimum) is a
        // model-level problem, NOT a provider-wide one. Never let such an error be mistaken for
        // quota exhaustion and trip the long provider cooldown.
        boolean parameterError = upstreamCode != null && (upstreamCode.contains("invalid_parameter")
                || upstreamCode.contains("validation_error") || upstreamCode.contains("badrequest"));

        boolean quotaExhausted = isQuotaCode(upstreamCode) || (!parameterError && isQuotaPhrase(lowerBody));
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
            if (type == ProviderFailureType.AUTHENTICATION) {
                ApiKeyPool pool = modelRegistry.getApiKeyPool(providerId);
                if (pool != null && apiKey != null && !apiKey.isEmpty()) pool.markKeyAuthenticationFailure(apiKey);
            }
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

    /**
     * Extracts a structured OpenAI-style error code/type from an upstream error body, e.g.
     * {@code {"error":{"code":"insufficient_quota","type":"insufficient_quota"}}}. Falls back to a
     * top-level {@code code} field. Returns {@code null} when the body is not JSON or carries no
     * recognizable code, letting the textual heuristics decide.
     */
    private static String extractUpstreamErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            Object parsed = UPSTREAM_ERROR_MAPPER.readValue(responseBody, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object error = map.get("error");
                if (error instanceof Map<?, ?> errorMap) {
                    String code = asLowerString(errorMap.get("code"));
                    if (code != null) return code;
                    String type = asLowerString(errorMap.get("type"));
                    if (type != null) return type;
                }
                return asLowerString(map.get("code"));
            }
        } catch (Exception ignored) {
            // Not JSON or unexpected shape - fall back to textual heuristics.
        }
        return null;
    }

    private static String asLowerString(Object value) {
        return value instanceof String s && !s.isBlank() ? s.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static boolean isQuotaCode(String upstreamCode) {
        return upstreamCode != null && (upstreamCode.contains("insufficient_quota")
                || upstreamCode.contains("quota_exceeded") || upstreamCode.contains("quota_exhausted")
                || upstreamCode.contains("quota reached") || upstreamCode.contains("billing_hard_limit")
                || upstreamCode.contains("billing_error"));
    }

    private static boolean isQuotaPhrase(String lowerBody) {
        return lowerBody.contains("quota exceeded") || lowerBody.contains("exceeded quota")
                || lowerBody.contains("insufficient quota") || lowerBody.contains("insufficient_quota")
                || lowerBody.contains("quota exhausted") || lowerBody.contains("quota-exhausted")
                || lowerBody.contains("api calls / month") || lowerBody.contains("monthly limit")
                || (lowerBody.contains("billing") && lowerBody.contains("limit"));
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
