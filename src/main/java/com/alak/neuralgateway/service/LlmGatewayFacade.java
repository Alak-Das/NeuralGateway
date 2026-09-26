package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.domain.routing.RoutingScore;
import com.alak.neuralgateway.ToolCallNormalizer;
import com.alak.neuralgateway.PayloadTelemetryService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final LlmProviderClient LlmProviderClient;
    private final ToolCallNormalizer toolCallNormalizer;
    private final PayloadTelemetryService payloadTelemetryService;
    private final ModelStatusService modelStatusService;
    private final SseNotificationService sseNotificationService;
    private final RoutingProperties routingProperties;
    private final RedisPersistenceService redisPersistenceService;

    public LlmGatewayFacade(ModelRegistry modelRegistry,
                            RoutingService routingService,
                            CircuitBreakerService circuitBreakerService,
                            HealthCheckService healthCheckService,
                            LlmProviderClient LlmProviderClient,
                            ToolCallNormalizer toolCallNormalizer,
                            PayloadTelemetryService payloadTelemetryService,
                            ModelStatusService modelStatusService,
                            SseNotificationService sseNotificationService,
                            RoutingProperties routingProperties,
                            RedisPersistenceService redisPersistenceService) {
        this.modelRegistry = modelRegistry;
        this.routingService = routingService;
        this.circuitBreakerService = circuitBreakerService;
        this.healthCheckService = healthCheckService;
        this.LlmProviderClient = LlmProviderClient;
        this.toolCallNormalizer = toolCallNormalizer;
        this.payloadTelemetryService = payloadTelemetryService;
        this.modelStatusService = modelStatusService;
        this.sseNotificationService = sseNotificationService;
        this.routingProperties = routingProperties;
        this.redisPersistenceService = redisPersistenceService;
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
            
            long startTime = System.currentTimeMillis();
            try {
                // Check circuit breaker
                if (!circuitBreakerService.isRequestPermitted(model.getId())) {
                    throw new IllegalStateException("Circuit breaker OPEN for model: " + model.getId());
                }

                // Upstream provider must always receive non-streaming request without stream_options
                // LlmController will format the final response into SSE chunks if client requested streaming
                Map<String, Object> upstreamRequest = new java.util.HashMap<>(requestBody);
                upstreamRequest.put("stream", false);
                upstreamRequest.put("model", model.getId());
                upstreamRequest.remove("stream_options");
                
                Map<String, Object> response = LlmProviderClient.call(model.getId(), upstreamRequest);
                
                long latency = System.currentTimeMillis() - startTime;
                
                // Update telemetry and health status on success
                routingService.updateEmaLatency(model.getId(), latency);
                circuitBreakerService.recordSuccess(model.getId());
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), true, latency, java.time.Instant.now(), null));
                
                // Normalize tool calls
                toolCallNormalizer.normalizeToolCalls(response, requestBody, transactionId);

                // Track token usage for requester
                long tokensUsed = extractTotalTokens(response);
                if (tokensUsed <= 0) {
                    tokensUsed = estimatedTokens;
                }
                if (tokensUsed > 0 && requester != null && !requester.isEmpty()) {
                    redisPersistenceService.incrementRequesterUsage(requester, tokensUsed);
                }
                
                // Decrement active connections
                routingService.decrementActiveConnections(model.getId());
                
                return response;
                
            } catch (LlmProviderClient.UpstreamServiceException e) {
                // 5xx errors - failover to next model
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                circuitBreakerService.recordFailure(model.getId(), e);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                routingService.decrementActiveConnections(model.getId());
                continue;
            } catch (IllegalArgumentException e) {
                // 4xx errors - don't failover, return immediately
                routingService.decrementActiveConnections(model.getId());
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                circuitBreakerService.recordFailure(model.getId(), e);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
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

                List<Map<String, Object>> streamChunks = LlmProviderClient.callStream(model.getId(), requestBody);
                
                // Convert to SSE format
                String sse = convertToSseFormat(streamChunks, model.getId());
                
                circuitBreakerService.recordSuccess(model.getId());
                routingService.decrementActiveConnections(model.getId());
                
                // Track estimated tokens for requester on successful stream
                if (estimatedTokens > 0 && requester != null && !requester.isEmpty()) {
                    redisPersistenceService.incrementRequesterUsage(requester, estimatedTokens);
                }
                
                return sse;
                
            } catch (LlmProviderClient.UpstreamServiceException e) {
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
        return result;
    }

    /**
     * Reset circuit breaker for a model.
     */
    public void resetCircuitBreaker(String modelId) {
        circuitBreakerService.resetCircuit(modelId);
        modelStatusService.initializeModel(modelId);
    }

    /**
     * Get requester telemetry.
     * 
     * @return list of requester usage counts formatted for UI
     */
    public List<Map<String, Object>> getRequesterTelemetry() {
        Map<String, Long> usageMap = redisPersistenceService.getRequesterUsage();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Map.Entry<String, Long> entry : usageMap.entrySet()) {
            Map<String, Object> map = new HashMap<>();
            map.put("requester", entry.getKey());
            map.put("count", entry.getValue());
            result.add(map);
        }
        
        // Sort by count descending
        result.sort((a, b) -> ((Long) b.get("count")).compareTo((Long) a.get("count")));
        return result;
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

    private long extractTotalTokens(Map<String, Object> response) {
        if (response == null || !response.containsKey("usage")) return 0;
        try {
            Object usageObj = response.get("usage");
            if (usageObj instanceof Map<?, ?> usage) {
                Object total = usage.get("total_tokens");
                if (total instanceof Number num) {
                    return num.longValue();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Get routing score for a model.
     */
    public RoutingScore getRoutingScore(String modelId) {
        return routingService.calculateRoutingScore(modelId);
    }
}
