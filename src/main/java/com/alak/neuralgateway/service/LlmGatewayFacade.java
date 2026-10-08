package com.alak.neuralgateway.service;

import com.alak.neuralgateway.config.RoutingProperties;
import com.alak.neuralgateway.config.LlmProvidersProperties;
import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.domain.routing.RoutingScore;
import com.alak.neuralgateway.ToolCallNormalizer;
import com.alak.neuralgateway.PayloadTelemetryService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final HealthCheckService healthCheckService;
    private final LlmProviderClient llmProviderClient;
    private final ToolCallNormalizer toolCallNormalizer;
    private final PayloadTelemetryService payloadTelemetryService;
    private final ModelStatusService modelStatusService;
    private final SseNotificationService sseNotificationService;
    private final RoutingProperties routingProperties;
    private final RedisPersistenceService redisPersistenceService;
    private final ObjectMapper objectMapper;
    private final TelemetryTraceService telemetryTraceService;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmGatewayFacade(ModelRegistry modelRegistry,
                            RoutingService routingService,
                            HealthCheckService healthCheckService,
                            LlmProviderClient llmProviderClient,
                            ToolCallNormalizer toolCallNormalizer,
                            PayloadTelemetryService payloadTelemetryService,
                            ModelStatusService modelStatusService,
                            SseNotificationService sseNotificationService,
                            RoutingProperties routingProperties,
                            RedisPersistenceService redisPersistenceService,
                            ObjectMapper objectMapper,
                            TelemetryTraceService telemetryTraceService) {
        this.modelRegistry = modelRegistry;
        this.routingService = routingService;
        this.healthCheckService = healthCheckService;
        this.llmProviderClient = llmProviderClient;
        this.toolCallNormalizer = toolCallNormalizer;
        this.payloadTelemetryService = payloadTelemetryService;
        this.modelStatusService = modelStatusService;
        this.sseNotificationService = sseNotificationService;
        this.routingProperties = routingProperties;
        this.redisPersistenceService = redisPersistenceService;
        this.objectMapper = objectMapper;
        this.telemetryTraceService = telemetryTraceService;
    }

    /**
     * Process a non-streaming chat completion request with resilient failover.
     *
     * @param requestBody   the OpenAI-compatible chat completion payload
     * @param requester     the client or agent identifier (e.g. "cline", "cursor")
     * @param pipelineName  name of target pipeline (e.g. "CODING", "REASONING", "VISION")
     * @return the normalized upstream LLM response
     * @throws IllegalStateException if all candidate models and providers are unavailable
     */
    public Map<String, Object> processChatCompletion(Map<String, Object> requestBody, String requester, String pipelineName) {
        Pipeline pipeline = Pipeline.valueOf(pipelineName.toUpperCase());
        sanitizeRequest(requestBody);

        if (payloadTelemetryService != null) {
            String payloadSummary = payloadTelemetryService.summarizeRequest(requestBody);
            if (payloadSummary != null) {
                log.info("Request payload: {}", payloadSummary);
            }
        }

        // Estimate tokens for context window validation
        int estimatedTokens = estimateTokens(requestBody);

        // Select candidate models
        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);

        if (candidates.isEmpty()) {
            throw noEligibleProvider(pipeline, null);
        }

        // If client targeted a specific physical model, prioritize it as the primary candidate if available
        Object requestedModelObj = requestBody.get("model");
        if (requestedModelObj instanceof String requestedModel && modelRegistry.isValidModel(requestedModel)) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getId().equalsIgnoreCase(requestedModel)) {
                    Model targeted = candidates.remove(i);
                    candidates.add(0, targeted);
                    log.info("Prioritized targeted model '{}' as primary candidate", requestedModel);
                    break;
                }
            }
        }

        // Try each candidate in priority order with failover
        Exception lastException = null;
        for (int i = 0; i < candidates.size(); i++) {
            Model model = candidates.get(i);

            // Increment usage tracking
            modelStatusService.incrementUsage(model.getId());

            // Track active connections
            routingService.incrementActiveConnections(model.getId());

            long startTime = System.currentTimeMillis();
            try {
                // Upstream provider must always receive non-streaming request without stream_options
                // LlmController will format the final response into SSE chunks if client requested streaming
                Map<String, Object> upstreamRequest = new java.util.HashMap<>(requestBody);
                upstreamRequest.put("stream", false);
                upstreamRequest.put("model", model.getId());
                upstreamRequest.remove("stream_options");
                applyMinMaxTokens(upstreamRequest, model);

                Map<String, Object> response = llmProviderClient.call(model.getId(), upstreamRequest);

                long latency = System.currentTimeMillis() - startTime;
                
                long tokensUsed = extractTotalTokens(response);
                if (tokensUsed <= 0) {
                    tokensUsed = estimatedTokens;
                }

                // Update telemetry and health status on success asynchronously to reduce response latency
                final long finalTokensUsed = tokensUsed;
                CompletableFuture.runAsync(() -> {
                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), true, latency, java.time.Instant.now(), null));
                    telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName, finalTokensUsed, 200, "/v1/chat/completions");

                    // Track token usage for requester
                    if (requester != null && !requester.isEmpty()) {
                        redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, finalTokensUsed, true, latency);
                    }
                     // Update EMA latency for successful calls
                     redisPersistenceService.saveEmaLatency(model.getId(), latency);
                });

                // Normalize tool calls
                toolCallNormalizer.normalizeToolCalls(response, requestBody);

                if (requestedModelObj instanceof String requestedModelAlias) {
                    response.put("model", requestedModelAlias);
                }
                return response;

            } catch (LlmProviderClient.UpstreamServiceException e) {
                // 5xx errors - failover to next model
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("Upstream failure for model '{}' ({}ms): {}. Failing over to next candidate...", model.getId(), latency, e.getMessage());
                
                CompletableFuture.runAsync(() -> {
                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                    telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, e.getStatusCode(), "/v1/chat/completions");
                    if (requester != null && !requester.isEmpty()) {
                        redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                    }
                });
                continue;
            } catch (IllegalArgumentException e) {
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                if (i < candidates.size() - 1 && e.getMessage() != null && 
                        (e.getMessage().contains("wrong_api_format") || e.getMessage().contains("unsupported") || e.getMessage().contains("validation_error") || e.getMessage().contains("multimodal"))) {
                    log.warn("Model '{}' rejected request format ({}ms): {}. Failing over to next candidate...", model.getId(), latency, e.getMessage());
                    // Format rejections are client payload format issues, not provider health failures.
                    // Failover immediately without penalizing the model.
                    continue;
                }
                // Genuine client 4xx errors - don't failover, return immediately
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("Unexpected error for model '{}' ({}ms): {}. Failing over to next candidate...", model.getId(), latency, e.getMessage());
                CompletableFuture.runAsync(() -> {
                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                    telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, 500, "/v1/chat/completions");
                    if (requester != null && !requester.isEmpty()) {
                        redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                    }
                });
                continue;
            } finally {
                // Decrement active connections
                routingService.decrementActiveConnections(model.getId());
            }
        }

        // All candidates exhausted
        throw noEligibleProvider(pipeline, lastException);
    }

    /**
     * Process a streaming chat completion request with resilient failover and guaranteed connection cleanup.
     *
     * @param requestBody  the OpenAI-compatible chat completion payload with stream=true
     * @param requester    the client or agent identifier (e.g. "cline", "cursor")
     * @param pipelineName  name of target pipeline (e.g. "CODING", "REASONING", "VISION")
     * @return a Flux of raw SSE chunk strings emitted by the upstream provider
     */
    public Flux<String> processStreamingChatCompletion(Map<String, Object> requestBody, String requester, String pipelineName) {
        Pipeline pipeline = Pipeline.valueOf(pipelineName.toUpperCase());
        sanitizeRequest(requestBody);

        if (payloadTelemetryService != null) {
            String payloadSummary = payloadTelemetryService.summarizeRequest(requestBody);
            if (payloadSummary != null) {
                log.info("Streaming request payload: {}", payloadSummary);
            }
        }

        int estimatedTokens = estimateTokens(requestBody);

        List<Model> candidates = routingService.selectModels(pipeline, estimatedTokens);

        if (candidates.isEmpty()) {
            return Flux.error(noEligibleProvider(pipeline, null));
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

        return streamCandidate(candidates, 0, requestBody, requester, pipelineName, estimatedTokens);
    }

    private Flux<String> streamCandidate(List<Model> candidates, int candidateIndex, Map<String, Object> requestBody, String requester, String pipelineName, int estimatedTokens) {
        if (candidateIndex >= candidates.size()) {
            return Flux.error(noEligibleProvider(Pipeline.valueOf(pipelineName.toUpperCase()), null));
        }

        Model model = candidates.get(candidateIndex);
        return Flux.defer(() -> {
            modelStatusService.incrementUsage(model.getId());
            routingService.incrementActiveConnections(model.getId());
            
            long startTime = System.currentTimeMillis();
            AtomicBoolean emittedAnyData = new AtomicBoolean(false);
            AtomicBoolean connectionReleased = new AtomicBoolean(false);
            AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
            Runnable releaseConnection = () -> {
                if (connectionReleased.compareAndSet(false, true)) {
                    routingService.decrementActiveConnections(model.getId());
                }
            };

            Map<String, Object> upstreamRequest = new HashMap<>(requestBody);
            upstreamRequest.put("stream", true);
            upstreamRequest.put("model", model.getId());
            applyMinMaxTokens(upstreamRequest, model);

            Flux<String> upstream;
            try {
                upstream = llmProviderClient.callStream(model.getId(), upstreamRequest)
                        .map(chunk -> {
                            Object requestedModelObj = requestBody.get("model");
                            if (requestedModelObj instanceof String requestedModelAlias && chunk.startsWith("{")) {
                                return chunk.replaceAll("\"model\"\\s*:\\s*\"[^\"]+\"", java.util.regex.Matcher.quoteReplacement("\"model\":\"" + requestedModelAlias + "\""));
                            }
                            return chunk;
                        })
                        .doOnNext(event -> emittedAnyData.set(true))
                        .doOnComplete(() -> {
                            long latency = System.currentTimeMillis() - startTime;
                            if (outcomeRecorded.compareAndSet(false, true)) {
                                CompletableFuture.runAsync(() -> {
                                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                                            model.getId(), true, latency, java.time.Instant.now(), null));
                                    // For streaming, we don't have exact token count until the end, so we'll use estimated
                                    telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName, estimatedTokens, 200, "/v1/chat/completions");
                                    if (requester != null && !requester.isEmpty()) {
                                        redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, estimatedTokens, true, latency);
                                    }
                                });
                            }
                        })
                        .doFinally(signalType -> releaseConnection.run());
            } catch (Throwable assemblyError) {
                // If callStream throws before returning a Flux (synchronous validation failure)
                releaseConnection.run();
                upstream = Flux.error(assemblyError);
            }

            return upstream
                    .doOnError(error -> {
                        long latency = System.currentTimeMillis() - startTime;
                        if (outcomeRecorded.compareAndSet(false, true)) {
                            CompletableFuture.runAsync(() -> {
                                int statusCode = 500;
                                String errorMsg = error.getMessage();
                                if (error instanceof LlmProviderClient.UpstreamServiceException upEx) {
                                    statusCode = upEx.getStatusCode();
                                    if (statusCode == 200) {
                                        statusCode = 503;
                                        errorMsg = "Premature stream termination (connection dropped)";
                                    } else {
                                        errorMsg = statusCode + " error";
                                    }
                                }
                                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                                        model.getId(), false, latency, java.time.Instant.now(), errorMsg));
                                telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, 
                                        statusCode, "/v1/chat/completions");
                                if (requester != null && !requester.isEmpty()) {
                                    redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                                }
                            });
                        }
                        releaseConnection.run();
                    })
                    .onErrorResume(error -> {
                        boolean canFailOver = !emittedAnyData.get();
                        if (canFailOver && candidateIndex + 1 < candidates.size()) {
                            log.warn("Streaming error for model '{}' ({}ms): {}. Failing over to next candidate...", model.getId(), (System.currentTimeMillis() - startTime), error.getMessage());
                            return streamCandidate(candidates, candidateIndex + 1, requestBody, requester, pipelineName, estimatedTokens);
                        }
                        if (canFailOver) {
                            return Flux.error(noEligibleProvider(Pipeline.valueOf(pipelineName.toUpperCase()),
                                     error instanceof Exception ex ? ex : new Exception(error)));
                        }
                        return Flux.error(error);
                    });
        });
    }

    private IllegalStateException noEligibleProvider(Pipeline pipeline, Exception cause) {
        String message = String.format("All available models for the '%s' pipeline are currently exhausted or unavailable.",
                pipeline.name().toLowerCase());
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private void applyMinMaxTokens(Map<String, Object> requestBody, Model model) {
        Integer maxTokens = model.getMaxTokens();
        if (maxTokens != null && maxTokens > 0) {
            requestBody.putIfAbsent("max_tokens", maxTokens);
        }
        Integer minTokens = model.getMinTokens();
        if (minTokens != null && minTokens > 0) {
            Object currentMaxTokens = requestBody.get("max_tokens");
            if (currentMaxTokens instanceof Number n && n.intValue() < minTokens) {
                requestBody.put("max_tokens", minTokens);
            }
        }
    }

    /**
     * Get calculated routing score for a model.
     *
     * @param modelId the ID of the model
     * @return the RoutingScore value object
     */
    public RoutingScore getRoutingScore(String modelId) {
        double ema = redisPersistenceService.getEmaLatency(modelId, 0.0);
        int active = routingService.getActiveConnections(modelId);
        int penalty = routingProperties.getConnectionPenaltyMs();
        int priority = modelRegistry.getModel(modelId).map(Model::getPriority).orElse(1);
        return new RoutingScore(ema, active, penalty, priority);
    }

    // ==================== Requester Usage Tracking ====================

    /**
     * Get requester usage statistics formatted for UI.
     *
     * @return list of requester usage counts formatted for UI
     */
    public List<Map<String, Object>> getRequesterTelemetry() {
        return redisPersistenceService.getRequesterTelemetryDetailed();
    }

    public List<Map<String, Object>> getRequesterHistory(String requester, int days) {
        return redisPersistenceService.getRequesterHistory(requester, days);
    }

    // ==================== Internal Helper Methods ====================

    /**
     * Sanitizes incoming requests to prevent 400 Bad Request errors from non-standard parameters.
     * Clients like Cline, Roo Code, or Cursor often send non-standard fields (e.g. 'thinking_effort',
     * Anthropic-style 'thinking' maps, or 'reasoning_effort' set to 'xhigh' or 'max').
     * Upstream OpenAI-compatible providers strictly allow only 'none', 'low', 'medium', or 'high'
     * for 'reasoning_effort', and reject unknown fields like 'thinking_effort' or 'thinking' with 400.
     */
    void sanitizeRequest(Map<String, Object> requestBody) {
        if (requestBody == null) return;

        // 1. Remove parameters that might cause issues with upstream providers
        requestBody.remove("stream_options");
        requestBody.remove("frequency_penalty");
        requestBody.remove("presence_penalty");
        requestBody.remove("logit_bias");
        requestBody.remove("user");

        // 2. Convert non-standard 'thinking_effort' to 'reasoning_effort'
        if (requestBody.containsKey("thinking_effort")) {
            Object te = requestBody.remove("thinking_effort");
            if (!requestBody.containsKey("reasoning_effort") && te != null) {
                requestBody.put("reasoning_effort", te);
            }
        }

        // 3. Convert Anthropic-style 'thinking' parameter to 'reasoning_effort'
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

        // 4. Strictly normalize 'reasoning_effort' to OpenAI standard values ('none', 'low', 'medium', 'high')
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
            Map<String, Object> promptParts = new HashMap<>();
            for (String field : List.of("messages", "tools", "tool_choice", "response_format")) {
                Object value = requestBody.get(field);
                if (value != null) promptParts.put(field, replaceImagePayloads(value));
            }
            String prompt = objectMapper.writeValueAsString(promptParts);
            double charsPerToken = routingProperties.getCharsPerToken() > 0 ? routingProperties.getCharsPerToken() : 3.5;
            int textTokens = (int) Math.ceil(prompt.length() / charsPerToken);
            int imageCount = countImages(requestBody.get("messages"));
            int tokensPerImage = routingProperties.getTokensPerImage() > 0 ? routingProperties.getTokensPerImage() : 2048;
            int defaultOutputTokens = routingProperties.getDefaultOutputTokens() > 0 ? routingProperties.getDefaultOutputTokens() : 4096;
            int outputTokens = requestBody.get("max_tokens") instanceof Number n ? Math.max(0, n.intValue()) : defaultOutputTokens;
            return Math.addExact(Math.addExact(textTokens, imageCount * tokensPerImage), outputTokens);
        } catch (Exception e) {
            return 0;
        }
    }

    private int countImages(Object messagesObject) {
        if (!(messagesObject instanceof List<?> messages)) return 0;
        int count = 0;
        for (Object message : messages) {
            if (message instanceof Map<?, ?> map && map.get("content") instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap && "image_url".equals(partMap.get("type"))) count++;
                }
            }
        }
        return count;
    }

    private Object replaceImagePayloads(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new HashMap<>();
            source.forEach((key, item) -> copy.put(String.valueOf(key), "image_url".equals(key) ? "[image]" : replaceImagePayloads(item)));
            return copy;
        }
        if (value instanceof List<?> source) {
            return source.stream().map(this::replaceImagePayloads).toList();
        }
        return value;
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
        } catch (Exception ignored) {
        }
        return 0; // Default return if extraction fails
    }
// ==================== LlmController Interface Methods ====================

    /**
     * Get statuses for all models.
     * 
     * @return map of model ID -> status information
     */
    public Map<String, Object> getModelStatuses() {
        Map<String, Object> statuses = new HashMap<>();
        Map<String, Model> allModels = modelRegistry.getModelCatalog();
        for (Map.Entry<String, Model> entry : allModels.entrySet()) {
            String modelId = entry.getKey();
            Model model = entry.getValue();
            
            // Get basic model info
            Map<String, Object> status = new HashMap<>();
            status.put("id", model.getId());
            status.put("name", model.getName());
            status.put("providerId", model.getProviderId());
            status.put("enabled", model.isEnabled());
            
            // Get health status from redisPersistenceService
            HealthCheckResult latestHealth = redisPersistenceService.getLatestHealthCheck(modelId).orElse(null);
            if (latestHealth != null) {
                status.put("healthy", latestHealth.isUp());
                status.put("latencyMs", latestHealth.getLatencyMs());
                status.put("lastCheck", latestHealth.getTimestamp().toString());
                status.put("errorMessage", latestHealth.getErrorMessage());
            } else {
                status.put("healthy", false);
                status.put("latencyMs", 0);
                status.put("lastCheck", null);
                status.put("errorMessage", "No health checks performed");
            }
            
            // Get model availability state
            boolean isAvailable = modelStatusService.isModelUp(modelId);
            status.put("available", isAvailable);
            
            // Get EMA latency
            double emaLatency = redisPersistenceService.getEmaLatency(modelId, 0.0);
            status.put("emaLatencyMs", emaLatency);
            
            statuses.put(modelId, status);
        }
        return statuses;
    }

    /**
     * Get all model statuses as a list.
     * 
     * @return list of all model statuses
     */
    public List<ModelStatus> getAllModelStatuses() {
        return modelStatusService.getAllStatuses();
    }

    /**
     * Subscribe to status updates.
     * 
     * @return SseEmitter for streaming model status updates
     */
    public SseEmitter subscribeToStatusUpdates() {
        SseEmitter emitter = sseNotificationService.subscribe();
        // Send initial state immediately so client doesn't have to wait for the next broadcast
        sseNotificationService.sendInitialState(emitter, java.util.Map.of(
                "models", modelStatusService.getAllStatuses(),
                "requesters", getRequesterTelemetry()
        ));
        return emitter;
    }

    /**
     * Ping a model to check its availability.
     * 
     * @param modelId the model ID to ping
     * @return health check result indicating if model is available and responsive
     */
    public HealthCheckResult pingModel(String modelId) {
        return healthCheckService.pingModel(modelId);
    }

    /**
     * Reset circuit breaker for a model.
     * 
     * @param modelId the model ID
     */
    public void resetCircuitBreaker(String modelId) {
        if (modelId == null || modelId.isBlank()) return;
        redisPersistenceService.resetConsecutiveErrors(modelId);
        modelStatusService.resetCircuitBreaker(modelId);
    }

    /**
     * Update dynamic model configuration and immediately sync the status cache.
     */
    public void updateModelConfig(String modelId, Map<String, Object> config) {
        modelRegistry.updateModelConfig(modelId, config);
        // Find canonical model ID in case relaxed lookup was used
        String canonicalId = modelRegistry.findModelRelaxed(modelId) != null 
                ? modelRegistry.findModelRelaxed(modelId).getId() 
                : modelId;
        modelStatusService.notifyModelConfigUpdated(canonicalId);
    }

}

