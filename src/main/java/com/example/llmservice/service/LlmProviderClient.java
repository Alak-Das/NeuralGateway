package com.example.llmservice.service;

import java.util.List;
import java.util.Map;

/**
 * Interface representing a generic LLM Provider (NVIDIA, OpenAI, Anthropic, etc).
 */
public interface LlmProviderClient {

    /**
     * Make a non-streaming call to the LLM API.
     *
     * @param modelId The ID of the model to use
     * @param request The request payload (OpenAI format)
     * @return The response payload (OpenAI format)
     */
    Map<String, Object> call(String modelId, Map<String, Object> request);

    /**
     * Make a streaming call to the LLM API.
     *
     * @param modelId The ID of the model to use
     * @param request The request payload (OpenAI format)
     * @return A list of response chunks (OpenAI format)
     */
    List<Map<String, Object>> callStream(String modelId, Map<String, Object> request);

    /**
     * Exception for upstream service failures (5xx).
     */
    class UpstreamServiceException extends RuntimeException {
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
