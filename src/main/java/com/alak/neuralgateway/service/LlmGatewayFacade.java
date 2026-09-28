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
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Facade orchestrating all LLM gateway services.
 * Provides a unified API for the controller layer.
 */
@Service
public class LlmGatewayFacade {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayFacade.class);

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

        if (payloadTelemetryService != null) {
            String payloadSummary = payloadTelemetryService.summarizeRequest(requestBody);
            if (payloadSummary != null) {
                log.info("[TxID: {}] Request payload: {}", transactionId, payloadSummary);
            }
        }
        
        // Estimate tokens for context window validation
        int estimatedTokens = estimateTokens(requestBody);

        // Select candidate models
        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);
        
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for pipeline: " + pipelineName);
        }

        // If client targeted a specific physical model, prioritize it as the primary candidate if available
        Object requestedModelObj = requestBody.get("model");
        if (requestedModelObj instanceof String requestedModel && modelRegistry.isValidModel(requestedModel)) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getId().equalsIgnoreCase(requestedModel)) {
                    Model targeted = candidates.remove(i);
                    candidates.add(0, targeted);
                    log.info("[TxID: {}] Prioritized targeted model '{}' as primary candidate", transactionId, requestedModel);
                    break;
                }
            }
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
                log.warn("[TxID: {}] Upstream failure for model '{}' ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
                circuitBreakerService.recordFailure(model.getId(), e);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                routingService.decrementActiveConnections(model.getId());
                continue;
            } catch (IllegalArgumentException e) {
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                if (i < candidates.size() - 1 && e.getMessage() != null &&
                        (e.getMessage().contains("wrong_api_format") || e.getMessage().contains("unsupported") || e.getMessage().contains("validation_error"))) {
                    log.warn("[TxID: {}] Model '{}' rejected request format ({}ms): {}. Failing over to next candidate...",
                            transactionId, model.getId(), latency, e.getMessage());
                    circuitBreakerService.recordFailure(model.getId(), e);
                    routingService.decrementActiveConnections(model.getId());
                    continue;
                }
                // Genuine client 4xx errors - don't failover, return immediately
                routingService.decrementActiveConnections(model.getId());
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("[TxID: {}] Model '{}' execution failed ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
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
    public Flux<String> processStreamingChatCompletion(Map<String, Object> requestBody,
                                                       String requester,
                                                       String transactionId,
                                                       String pipelineName) {
        Pipeline pipeline = Pipeline.valueOf(pipelineName.toUpperCase());
        sanitizeRequest(requestBody);

        if (payloadTelemetryService != null) {
            String payloadSummary = payloadTelemetryService.summarizeRequest(requestBody);
            if (payloadSummary != null) {
                log.info("[TxID: {}] Streaming request payload: {}", transactionId, payloadSummary);
            }
        }

        int estimatedTokens = estimateTokens(requestBody);

        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);
        
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available models for pipeline: " + pipelineName);
        }

        Object requestedModelObj = requestBody.get("model");
        if (requestedModelObj instanceof String requestedModel && modelRegistry.isValidModel(requestedModel)) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getId().equalsIgnoreCase(requestedModel)) {
                    Model targeted = candidates.remove(i);
                    candidates.add(0, targeted);
                    break;
                }
            }
        }

        return streamCandidate(candidates, 0, requestBody, requester, transactionId, pipelineName, estimatedTokens);
    }

    private Flux<String> streamCandidate(List<Model> candidates,
                                         int candidateIndex,
                                         Map<String, Object> requestBody,
                                         String requester,
                                         String transactionId,
                                         String pipelineName,
                                         int estimatedTokens) {
        if (candidateIndex >= candidates.size()) {
            return Flux.error(new IllegalStateException("All models failed for pipeline: " + pipelineName));
        }

        Model model = candidates.get(candidateIndex);
        return Flux.defer(() -> {
            modelStatusService.incrementUsage(model.getId());
            routingService.incrementActiveConnections(model.getId());

            if (!circuitBreakerService.isRequestPermitted(model.getId())) {
                routingService.decrementActiveConnections(model.getId());
                return streamCandidate(candidates, candidateIndex + 1, requestBody, requester,
                        transactionId, pipelineName, estimatedTokens);
            }

            long startTime = System.currentTimeMillis();
            AtomicBoolean emittedAnyData = new AtomicBoolean(false);
            AtomicBoolean connectionReleased = new AtomicBoolean(false);
            AtomicBoolean circuitOutcomeRecorded = new AtomicBoolean(false);
            Runnable releaseConnection = () -> {
                if (connectionReleased.compareAndSet(false, true)) {
                    routingService.decrementActiveConnections(model.getId());
                }
            };

            Map<String, Object> upstreamRequest = new java.util.HashMap<>(requestBody);
            upstreamRequest.put("stream", true);
            upstreamRequest.put("model", model.getId());

            Flux<String> upstream = LlmProviderClient.callStream(model.getId(), upstreamRequest)
                    .doOnNext(event -> emittedAnyData.set(true))
                    .doOnComplete(() -> {
                        long latency = System.currentTimeMillis() - startTime;
                        routingService.updateEmaLatency(model.getId(), latency);
                        if (circuitOutcomeRecorded.compareAndSet(false, true)) {
                            circuitBreakerService.recordSuccess(model.getId());
                        }
                        releaseConnection.run();
                        modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                                model.getId(), true, latency, java.time.Instant.now(), null));
                        if (estimatedTokens > 0 && requester != null && !requester.isEmpty()) {
                            redisPersistenceService.incrementRequesterUsage(requester, estimatedTokens);
                        }
                    })
                    .doOnError(error -> releaseConnection.run())
                    .doOnCancel(() -> {
                        releaseConnection.run();
                        if (circuitOutcomeRecorded.compareAndSet(false, true)) {
                            circuitBreakerService.releasePermission(model.getId());
                        }
                    });

            return upstream.onErrorResume(error -> {
                long latency = System.currentTimeMillis() - startTime;
                boolean formatError = error instanceof IllegalArgumentException
                        && error.getMessage() != null
                        && (error.getMessage().contains("wrong_api_format")
                        || error.getMessage().contains("unsupported")
                        || error.getMessage().contains("validation_error"));

                boolean recordAsFailure = !(error instanceof IllegalArgumentException) || formatError;
                if (circuitOutcomeRecorded.compareAndSet(false, true)) {
                    if (recordAsFailure) {
                        circuitBreakerService.recordFailure(model.getId(), error);
                    } else {
                        circuitBreakerService.releasePermission(model.getId());
                    }
                }
                if (recordAsFailure) {
                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                            model.getId(), false, latency, java.time.Instant.now(), error.getMessage()));
                }

                boolean canFailOver = !emittedAnyData.get()
                        && recordAsFailure;
                if (canFailOver) {
                    log.warn("[TxID: {}] Streaming error for model '{}': {}. Failing over...",
                            transactionId, model.getId(), error.getMessage());
                    return streamCandidate(candidates, candidateIndex + 1, requestBody, requester,
                            transactionId, pipelineName, estimatedTokens);
                }
                return Flux.error(error);
            });
        });
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
     * Clients like Cline, Roo Code, or Cursor often send non-standard fields (e.g. 'thinking_effort',
     * Anthropic-style 'thinking' maps, or 'reasoning_effort' set to 'xhigh' or 'max').
     * Upstream OpenAI-compatible providers strictly allow only 'none', 'low', 'medium', or 'high'
     * for 'reasoning_effort', and reject unknown fields like 'thinking_effort' or 'thinking' with 400 wrong_api_format.
     */
    void sanitizeRequest(Map<String, Object> requestBody) {
        if (requestBody == null) return;

        // 1. Convert non-standard 'thinking_effort' to 'reasoning_effort'
        if (requestBody.containsKey("thinking_effort")) {
            Object te = requestBody.remove("thinking_effort");
            if (!requestBody.containsKey("reasoning_effort") && te != null) {
                requestBody.put("reasoning_effort", te);
            }
        }

        // 2. Convert Anthropic-style 'thinking' parameter to 'reasoning_effort'
        if (requestBody.containsKey("thinking")) {
            Object thinking = requestBody.remove("thinking");
            if (!requestBody.containsKey("reasoning_effort") && thinking instanceof Map<?, ?> tMap) {
                Object budget = tMap.get("budget_tokens");
                if (budget instanceof Number n) {
                    if (n.intValue() > 8000) {
                        requestBody.put("reasoning_effort", "high");
                    } else if (n.intValue() > 2000) {
                        requestBody.put("reasoning_effort", "medium");
                    } else if (n.intValue() > 0) {
                        requestBody.put("reasoning_effort", "low");
                    }
                }
            }
        }

        // 3. Strictly normalize 'reasoning_effort' to OpenAI standard values ('none', 'low', 'medium', 'high')
        if (requestBody.containsKey("reasoning_effort")) {
            Object effortObj = requestBody.get("reasoning_effort");
            if (effortObj == null) {
                requestBody.remove("reasoning_effort");
            } else {
                String effort = effortObj.toString().trim().toLowerCase(java.util.Locale.ROOT);
                switch (effort) {
                    case "xhigh", "max", "maximum", "very_high", "very-high", "extra_high", "extra-high", "high" ->
                        requestBody.put("reasoning_effort", "high");
                    case "medium", "med", "mid", "moderate" ->
                        requestBody.put("reasoning_effort", "medium");
                    case "low", "min", "minimum", "minimal" ->
                        requestBody.put("reasoning_effort", "low");
                    case "none", "off", "false", "disabled", "0" ->
                        requestBody.put("reasoning_effort", "none");
                    default -> {
                        if (effort.contains("hi") || effort.contains("max")) {
                            requestBody.put("reasoning_effort", "high");
                        } else if (effort.contains("med") || effort.contains("mid")) {
                            requestBody.put("reasoning_effort", "medium");
                        } else if (effort.contains("low") || effort.contains("min")) {
                            requestBody.put("reasoning_effort", "low");
                        } else if (effort.contains("no") || effort.contains("off") || effort.contains("dis")) {
                            requestBody.put("reasoning_effort", "none");
                        } else {
                            // Unsupported value - remove to prevent 400 Bad Request
                            requestBody.remove("reasoning_effort");
                        }
                    }
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
