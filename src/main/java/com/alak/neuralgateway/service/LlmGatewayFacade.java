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
     * @param transactionId unique transaction tracking ID
     * @param pipelineName  name of target pipeline (e.g. "CODING", "REASONING", "VISION")
     * @return the normalized upstream LLM response
     * @throws IllegalStateException if all candidate models and providers are unavailable
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
            throw noEligibleProvider(pipeline, null);
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
            if (!isProviderAvailable(model)) {
                log.info("[TxID: {}] Skipping model '{}' because provider '{}' is in cooldown", transactionId,
                        model.getId(), model.getProviderId());
                continue;
            }
            
            // Check if provider is marked as unavailable in Redis
            String providerId = model.getProviderId();
            if (providerId != null && !providerId.isBlank() && 
                redisPersistenceService.getProviderUnavailableReason(providerId) != null) {
                log.info("[TxID: {}] Skipping model '{}' because provider '{}' is marked as unavailable", 
                        transactionId, model.getId(), providerId);
                continue;
            }

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

                // Update telemetry and health status on success
                                recordProviderSuccess(model);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), true, latency, java.time.Instant.now(), null));
                long tokensUsed = extractTotalTokens(response);
                if (tokensUsed <= 0) {
                    tokensUsed = estimatedTokens;
                }
                telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName, tokensUsed, 200, "/v1/chat/completions");

                // Normalize tool calls
                toolCallNormalizer.normalizeToolCalls(response, requestBody, transactionId);

                // Track token usage for requester
                if (requester != null && !requester.isEmpty()) {
                    redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, tokensUsed, true, latency);
                }

                if (requestedModelObj instanceof String requestedModelAlias) {
                    response.put("model", requestedModelAlias);
                }
                 // Update EMA latency for successful calls
                 redisPersistenceService.saveEmaLatency(model.getId(), latency);
                return response;

            } catch (LlmProviderClient.UpstreamServiceException e) {
                // 5xx errors - failover to next model
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("[TxID: {}] Upstream failure for model '{}' ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
                recordProviderFailure(model);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, e.getStatusCode(), "/v1/chat/completions");
                if (requester != null && !requester.isEmpty()) {
                    redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                }
                 // Increment consecutive errors and check if provider should be marked as unavailable
                 redisPersistenceService.incrementProviderConsecutiveErrors(providerId);
                 // If error threshold exceeded, mark provider as unavailable for 60 seconds
                 if (redisPersistenceService.getProviderConsecutiveErrors(providerId) >= 3) {
                     redisPersistenceService.setProviderUnavailable(providerId, "Too many consecutive errors", Duration.ofSeconds(60));
                 }
                continue;
            } catch (IllegalArgumentException e) {
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                if (i < candidates.size() - 1 && e.getMessage() != null && 
                        (e.getMessage().contains("wrong_api_format") || e.getMessage().contains("unsupported") || e.getMessage().contains("validation_error") || e.getMessage().contains("multimodal"))) {
                    log.warn("[TxID: {}] Model '{}' rejected request format ({}ms): {}. Failing over to next candidate...",
                            transactionId, model.getId(), latency, e.getMessage());
                    recordProviderFailure(model);
                    continue;
                }
                // Genuine client 4xx errors - don't failover, return immediately
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("[TxID: {}] Unexpected error for model '{}' ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
                recordProviderFailure(model);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, 500, "/v1/chat/completions");
                if (requester != null && !requester.isEmpty()) {
                    redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                }
                redisPersistenceService.incrementProviderConsecutiveErrors(providerId);
                if (redisPersistenceService.getProviderConsecutiveErrors(providerId) >= 3) {
                    redisPersistenceService.setProviderUnavailable(providerId, "Too many consecutive errors", Duration.ofSeconds(60));
                }
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
     * @param transactionId unique transaction tracking ID
     * @param pipelineName  name of target pipeline (e.g. "CODING", "REASONING", "VISION")
     * @return a Flux of raw SSE chunk strings emitted by the upstream provider
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
            return Flux.error(noEligibleProvider(Pipeline.valueOf(pipelineName.toUpperCase()), null));
        }

        Model model = candidates.get(candidateIndex);
        return Flux.defer(() -> {
            if (!isProviderAvailable(model)) {
                log.info("[TxID: {}] Skipping model '{}' because provider '{}' is in cooldown", transactionId,
                        model.getId(), model.getProviderId());
                return streamCandidate(candidates, candidateIndex + 1, requestBody, requester,
                        transactionId, pipelineName, estimatedTokens);
            }
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
                                return chunk.replaceAll("\\\"model\\\"\\\\s*:\\\\s*\\\"[^\\\"]+\\\"", "\\\"model\\\":\\\"\" + requestedModelAlias + \"\\\"\"");
                            }
                            return chunk;
                        })
                        .doOnNext(event -> emittedAnyData.set(true))
                        .doOnComplete(() -> {
                            long latency = System.currentTimeMillis() - startTime;
                            if (outcomeRecorded.compareAndSet(false, true)) {
                               recordProviderSuccess(model);
                                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                                        model.getId(), true, latency, java.time.Instant.now(), null));
                                // For streaming, we don't have exact token count until the end, so we'll use estimated
                                telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName, estimatedTokens, 200, "/v1/chat/completions");
                            }
                            if (requester != null && !requester.isEmpty()) {
                                redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, estimatedTokens, true, latency);
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
                            recordProviderFailure(model);
                            modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                                    model.getId(), false, latency, java.time.Instant.now(), 
                                    error instanceof LlmProviderClient.UpstreamServiceException ? 
                                            ((LlmProviderClient.UpstreamServiceException) error).getStatusCode() + " error" : 
                                            error.getMessage()));
                            telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName, 0, 
                                    error instanceof LlmProviderClient.UpstreamServiceException ? 
                                            ((LlmProviderClient.UpstreamServiceException) error).getStatusCode() : 500, 
                                    "/v1/chat/completions");
                            if (requester != null && !requester.isEmpty()) {
                                redisPersistenceService.recordRequesterMetrics(requester, model.getId(), pipelineName, 0, false, latency);
                            }
                        }
                        releaseConnection.run();
                    })
                    .onErrorResume(error -> {
                        boolean canFailOver = !emittedAnyData.get();
                        if (canFailOver && candidateIndex + 1 < candidates.size()) {
                            log.warn("[TxID: {}] Streaming error for model '{}' ({}ms): {}. Failing over to next candidate...",
                                    transactionId, model.getId(), (System.currentTimeMillis() - startTime), error.getMessage());
                            return streamCandidate(candidates, candidateIndex + 1, requestBody, requester,
                                    transactionId, pipelineName, estimatedTokens);
                        }
                        return Flux.error(error);
                    });
        });
    }

    private IllegalStateException noEligibleProvider(Pipeline pipeline, Exception cause) {
        Set<String> providers = modelRegistry.getModelsByPipeline(pipeline).stream()
                .filter(Model::isEnabled)
                .map(Model::getProviderId).collect(Collectors.toSet());
        Map<String, String> unavailableReasons = new HashMap<>();
        for (String pId : providers) {
            String reason = redisPersistenceService.getProviderUnavailableReason(pId);
            if (reason != null) {
                unavailableReasons.put(pId, reason);
            }
        }
        String details = unavailableReasons.isEmpty() ? "" : " Provider cooldowns: " + unavailableReasons;
        String message = String.format("All available models for the '%s' pipeline are currently exhausted or unavailable.%s",
                pipeline.name().toLowerCase(), details);
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private boolean isProviderAvailable(Model model) {
        String providerId = model.getProviderId();
        if (providerId == null || providerId.isBlank()) {
            return true; // No provider specified, assume available
        }
        return redisPersistenceService.getProviderUnavailableReason(providerId) == null;
    }

    private void recordProviderSuccess(Model model) {
        // Reset consecutive errors and clear unavailability for this provider on success
        String providerId = model.getProviderId();
        if (providerId != null && !providerId.isBlank()) {
            redisPersistenceService.resetProviderConsecutiveErrors(providerId);
            redisPersistenceService.clearProviderUnavailable(providerId);
        }
    }

    private void recordProviderFailure(Model model) {
        // Track consecutive errors for this provider
        String providerId = model.getProviderId();
        if (providerId != null && !providerId.isBlank()) {
            redisPersistenceService.incrementProviderConsecutiveErrors(providerId);
        }
    }

    private void applyMinMaxTokens(Map<String, Object> requestBody, Model model) {
        Integer maxTokens = model.getMaxTokens();
        if (maxTokens != null && maxTokens > 0) {
            requestBody.putIfAbsent("max_tokens", maxTokens);
        }
        Integer minTokens = model.getMinTokens();
        if (minTokens != null && minTokens > 0) {
            Object currentMaxTokens = requestBody.get("max_tokens");
            if (currentMaxTokens instanceof Integer currentMax && currentMax < minTokens) {
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

    // ==================== Internal Helper Methods ====================

    private void sanitizeRequest(Map<String, Object> requestBody) {
        // Remove parameters that might cause issues with upstream providers
        requestBody.remove("stream_options");
        requestBody.remove("frequency_penalty");
        requestBody.remove("presence_penalty");
        requestBody.remove("logit_bias");
        requestBody.remove("user");
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
            
            // Get circuit breaker state
            boolean isAvailable = isProviderAvailable(model);
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
        return sseNotificationService.subscribe();
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
        modelRegistry.getModel(modelId).ifPresent(model -> {
            String providerId = model.getProviderId();
            if (providerId != null && !providerId.isBlank()) {
                // Reset consecutive errors and clear unavailability
                                  recordProviderSuccess(model);
                redisPersistenceService.clearProviderUnavailable(providerId); // Mark as available immediately
            }
        });
    }

}
