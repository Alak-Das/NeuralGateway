package com.example.llmservice.service;

import com.example.llmservice.config.RoutingProperties;
import com.example.llmservice.domain.health.HealthCheckResult;
import com.example.llmservice.domain.model.Model;
import com.example.llmservice.domain.model.Model.Pipeline;
import com.example.llmservice.domain.ModelStatus;
import com.example.llmservice.domain.routing.RoutingScore;
import com.example.llmservice.ToolCallNormalizer;
import com.example.llmservice.PayloadTelemetryService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade orchestrating all LLM gateway services.
 * Provides a unified API for the controller layer.
 */
@Service
public class LlmGatewayFacade {

    private final ModelRegistry modelRegistry;
    private final RoutingService routingService;
    private final CircuitBreakerService circuitBreakerService;
    private final HealthCheckService healthCheckService;
    private final NvidiaLlmClient nvidiaLlmClient;
    private final ToolCallNormalizer toolCallNormalizer;
    private final PayloadTelemetryService payloadTelemetryService;
    private final ModelStatusService modelStatusService;
    private final SseNotificationService sseNotificationService;
    private final RoutingProperties routingProperties;

    public LlmGatewayFacade(ModelRegistry modelRegistry,
                            RoutingService routingService,
                            CircuitBreakerService circuitBreakerService,
                            HealthCheckService healthCheckService,
                            NvidiaLlmClient nvidiaLlmClient,
                            ToolCallNormalizer toolCallNormalizer,
                            PayloadTelemetryService payloadTelemetryService,
                            ModelStatusService modelStatusService,
                            SseNotificationService sseNotificationService,
                            RoutingProperties routingProperties) {
        this.modelRegistry = modelRegistry;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.healthCheckService = healthCheckService;
        this.nvidiaLlmClient = nvidiaLlmClient;
        this.toolCallNormalizer = toolCallNormalizer;
        this.payloadTelemetryService = payloadTelemetryService;
        this.modelStatusService = modelStatusService;
        this.sseNotificationService = sseNotificationService;
        this.routingProperties = routingProperties;
    }

    /**
     * Process a chat completion request for the specified pipeline.
     */
    public Map<String, Object> processChatCompletion(Map<String, Object> requestBody, 
                                                     String requester, 
                                                     String transactionId, 
                                                     String pipelineName) {
        Pipeline pipeline = Pipeline.valueOf(pipelineName.toUpperCase());
        sanitizeRequest(requestBody);
        
        // Estimate tokens for context window validation
        int estimatedTokens = estimateTokens(requestBody);
        
        // Select candidate models
        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);
        
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for pipeline: " + pipelineName);
        }

        // Try each candidate with failover
        Exception lastException = null;
        for (int i = 0; i < candidates.size(); i++) {
            Model model = candidates.get(i);
            
            // Increment usage tracking
            modelStatusService.incrementUsage(model.getId());
            
            // Track active connections
            routingService.incrementActiveConnections(model.getId());
            
            try {
                // Check circuit breaker
                if (!circuitBreakerService.isRequestPermitted(model.getId())) {
                    throw new IllegalStateException("Circuit breaker OPEN for model: " + model.getId());
                }

                long startTime = System.currentTimeMillis();
                
                // Determine if streaming
                Boolean stream = (Boolean) requestBody.get("stream");
                boolean isStream = Boolean.TRUE.equals(stream);
                
                Map<String, Object> response;
                if (isStream) {
                    List<Map<String, Object>> streamChunks = nvidiaLlmClient.callStream(model.getId(), requestBody);
                    // For streaming, we return the collected chunks - controller handles SSE formatting
                    response = Map.of("stream_chunks", streamChunks);
                } else {
                    response = nvidiaLlmClient.call(model.getId(), requestBody);
                }
                
                long latency = System.currentTimeMillis() - startTime;
                
                // Update telemetry on success
                routingService.updateEmaLatency(model.getId(), latency);
                circuitBreakerService.recordSuccess(model.getId());
                
                // Normalize tool calls
                toolCallNormalizer.normalizeToolCalls(response, requestBody, transactionId);
                
                // Decrement active connections
                routingService.decrementActiveConnections(model.getId());
                
                return response;
                
            } catch (NvidiaLlmClient.UpstreamServiceException e) {
                // 5xx errors - failover to next model
                lastException = e;
                routingService.decrementActiveConnections(model.getId());
                continue;
            } catch (IllegalArgumentException e) {
                // 4xx errors - don't failover, return immediately
                routingService.decrementActiveConnections(model.getId());
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                lastException = e;
                routingService.decrementActiveConnections(model.getId());
                continue;
            }
        }

        // All candidates exhausted
        throw new IllegalStateException("All models failed for pipeline: " + pipelineName, lastException);
    }

    /**
     * Process a streaming chat completion request.
     */
    public String processStreamingChatCompletion(Map<String, Object> requestBody, 
                                                 String requester, 
                                                 String transactionId, 
                                                 String pipelineName) {
        Pipeline pipeline = Pipeline.valueOf(pipelineName.toUpperCase());
        sanitizeRequest(requestBody);
        int estimatedTokens = estimateTokens(requestBody);
        
        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);
        
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for pipeline: " + pipelineName);
        }

        // Try each candidate
        for (Model model : candidates) {
            modelStatusService.incrementUsage(model.getId());
            routingService.incrementActiveConnections(model.getId());
            
            try {
                if (!circuitBreakerService.isRequestPermitted(model.getId())) {
                    throw new IllegalStateException("Circuit breaker OPEN for model: " + model.getId());
                }

                List<Map<String, Object>> streamChunks = nvidiaLlmClient.callStream(model.getId(), requestBody);
                
                // Convert to SSE format
                String sse = convertToSseFormat(streamChunks, model.getId());
                
                circuitBreakerService.recordSuccess(model.getId());
                routingService.decrementActiveConnections(model.getId());
                
                return sse;
                
            } catch (NvidiaLlmClient.UpstreamServiceException e) {
                circuitBreakerService.recordFailure(model.getId(), e);
                routingService.decrementActiveConnections(model.getId());
                continue;
            } catch (Exception e) {
                circuitBreakerService.recordFailure(model.getId(), e);
                routingService.decrementActiveConnections(model.getId());
                continue;
            }
        }

        throw new IllegalStateException("All models failed for pipeline: " + pipelineName);
    }

    /**
     * Convert streaming chunks to SSE format.
     */
    private String convertToSseFormat(List<Map<String, Object>> chunks, String modelId) {
        StringBuilder sse = new StringBuilder();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        
        try {
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> chunk = chunks.get(i);
                
                // Add model if missing
                if (!chunk.containsKey("model")) {
                    chunk.put("model", modelId);
                }
                
                sse.append("data: ").append(mapper.writeValueAsString(chunk)).append("\n\n");
            }
            sse.append("data: [DONE]\n\n");
        } catch (Exception e) {
            throw new RuntimeException("Failed to format SSE", e);
        }
        
        return sse.toString();
    }

    /**
     * Get all model statuses.
     */
    public List<ModelStatus> getModelStatuses() {
        return modelStatusService.getAllStatuses();
    }

    /**
     * Get SSE emitter for real-time updates.
     */
    public SseEmitter subscribeToStatusUpdates() {
        SseEmitter emitter = sseNotificationService.subscribe();
        sseNotificationService.sendInitialState(emitter, getModelStatuses());
        return emitter;
    }

    /**
     * Manually ping a model.
     */
    public HealthCheckResult pingModel(String modelId) {
        HealthCheckResult result = healthCheckService.pingModel(modelId);
        modelStatusService.updateStatus(modelId, result);
        sseNotificationService.broadcast(modelStatusService.getAllStatuses());
        return result;
    }

    /**
     * Reset circuit breaker for a model.
     */
    public void resetCircuitBreaker(String modelId) {
        circuitBreakerService.resetCircuit(modelId);
        modelStatusService.initializeModel(modelId);
        sseNotificationService.broadcast(modelStatusService.getAllStatuses());
    }

    /**
     * Get requester telemetry (placeholder - to be implemented).
     */
    public List<Map<String, Object>> getRequesterTelemetry() {
        // TODO: Implement requester tracking
        return List.of();
    }

    /**
     * Estimate token count from request body.
     */
        /**
     * Sanitizes incoming requests to prevent 400 Bad Request errors from non-standard parameters.
     */
    private void sanitizeRequest(Map<String, Object> requestBody) {
        if (requestBody == null) return;
        
        // Cline / OpenAI compat clients often send 'thinking_effort' or 'reasoning_effort' = 'xhigh'
        // Some models (like Kimi K3) strictly reject 'xhigh', requiring 'low', 'high', or 'max'.
        String[] effortKeys = {"thinking_effort", "reasoning_effort"};
        for (String key : effortKeys) {
            if (requestBody.containsKey(key)) {
                Object effort = requestBody.get(key);
                if ("xhigh".equals(effort)) {
                    requestBody.put(key, "max"); // Map to highest supported equivalent
                }
            }
        }
    }

    private int estimateTokens(Map<String, Object> requestBody) {
        if (requestBody == null || !requestBody.containsKey("messages")) return 0;
        try {
            Object msgsObj = requestBody.get("messages");
            if (!(msgsObj instanceof List<?> messages)) return 0;
            int estimatedTokens = 0;
            for (Object mObj : messages) {
                if (!(mObj instanceof Map<?, ?> m)) continue;
                Object content = m.get("content");
                if (content instanceof String str) {
                    estimatedTokens += str.length() / 4;
                } else if (content instanceof List<?> parts) {
                    for (Object partObj : parts) {
                        if (partObj instanceof Map<?, ?> part) {
                            String type = (String) part.get("type");
                            if ("text".equals(type) && part.get("text") instanceof String t) {
                                estimatedTokens += t.length() / 4;
                            } else if ("image_url".equals(type)) {
                                estimatedTokens += 1000;
                            }
                        }
                    }
                }
            }
            return estimatedTokens;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get routing score for a model.
     */
    public RoutingScore getRoutingScore(String modelId) {
        return routingService.calculateRoutingScore(modelId);
    }
}