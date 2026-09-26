package com.example.llmservice.service;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around WebClient for NVIDIA NIM API calls.
 * Handles authentication, request/response formatting, and error mapping.
 */
@Service
public class NvidiaLlmClient {

    private final WebClient webClient;
    private final String apiKey;

    public NvidiaLlmClient(WebClient.Builder webClientBuilder,
                           @Value("${nvidia.api.base-url}") String baseUrl,
                           @Value("${nvidia.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Make a non-streaming call to the NVIDIA API.
     */
    @Retry(name = "nvidiaApi")
    public Map<String, Object> call(String modelId, Map<String, Object> request) {
        request.put("model", modelId);
        request.put("stream", false);

        try {
            return webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw mapWebClientException(e);
        }
    }

    /**
     * Make a streaming call to the NVIDIA API.
     */
    @Retry(name = "nvidiaApi")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> callStream(String modelId, Map<String, Object> request) {
        request.put("model", modelId);
        request.put("stream", true);

        try {
            // For streaming, we need to collect all chunks
            List<?> rawList = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(Map.class)
                    .collectList()
                    .block();
            
            if (rawList == null) {
                return new ArrayList<>();
            }
            
            return (List<Map<String, Object>>) rawList;
        } catch (WebClientResponseException e) {
            throw mapWebClientException(e);
        }
    }

    /**
     * Map WebClient exceptions to domain exceptions with useful error messages.
     */
    private RuntimeException mapWebClientException(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String message = (body != null && !body.isBlank()) ? body : ("Upstream API error: " + e.getStatusCode());
        
        if (e.getStatusCode().is4xxClientError()) {
            return new IllegalArgumentException(message);
        } else if (e.getStatusCode().is5xxServerError()) {
            return new UpstreamServiceException(message, e.getStatusCode().value());
        }
        
        return new RuntimeException(message);
    }

    /**
     * Exception for upstream service failures (5xx).
     */
    public static class UpstreamServiceException extends RuntimeException {
        private final int statusCode;

        public UpstreamServiceException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}