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
     * Process a non-streaming chat completion request for the specified pipeline with automatic failover.
     *
     * @param requestBody  the OpenAI-compatible chat completion payload
     * @param requester    the client or agent identifier (e.g. "cline", "cursor")
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
                telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName);

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

                if (requestedModelObj instanceof String requestedModelAlias) {
                    response.put("model", requestedModelAlias);
                }
                return response;

            } catch (LlmProviderClient.UpstreamServiceException e) {
                // 5xx errors - failover to next model
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("[TxID: {}] Upstream failure for model '{}' ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
                recordProviderFailure(model, e);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName);
                continue;
            } catch (IllegalArgumentException e) {
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                if (i < candidates.size() - 1 && e.getMessage() != null &&
                        (e.getMessage().contains("wrong_api_format") || e.getMessage().contains("unsupported") || e.getMessage().contains("validation_error"))) {
                    log.warn("[TxID: {}] Model '{}' rejected request format ({}ms): {}. Failing over to next candidate...",
                            transactionId, model.getId(), latency, e.getMessage());
                    continue;
                }
                // Genuine client 4xx errors - don't failover, return immediately
                throw e;
            } catch (Exception e) {
                // Other errors - failover
                long latency = System.currentTimeMillis() - startTime;
                lastException = e;
                log.warn("[TxID: {}] Model '{}' execution failed ({}ms): {}. Failing over to next candidate...",
                        transactionId, model.getId(), latency, e.getMessage());
                recordProviderFailure(model, e);
                modelStatusService.updateStatus(model.getId(), new HealthCheckResult(model.getId(), false, latency, java.time.Instant.now(), e.getMessage()));
                telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName);
                continue;
            } finally {
                // Release exactly once for success, failover, and exceptions thrown
                // while processing a successful upstream response.
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
            throw noEligibleProvider(pipeline, null);
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
                                return chunk.replaceAll("\"model\"\\s*:\\s*\"[^\"]+\"", "\"model\":\"" + requestedModelAlias + "\"");
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
                                telemetryTraceService.recordTrace(requester, model.getId(), latency, true, pipelineName);
                            }
                            if (estimatedTokens > 0 && requester != null && !requester.isEmpty()) {
                                redisPersistenceService.incrementRequesterUsage(requester, estimatedTokens);
                            }
                        })
                        .doFinally(signalType -> releaseConnection.run());
            } catch (Throwable assemblyError) {
                // If callStream throws before returning a Flux (synchronous validation failure)
                releaseConnection.run();
                upstream = Flux.error(assemblyError);
            }

            return upstream.onErrorResume(error -> {
                long latency = System.currentTimeMillis() - startTime;
                boolean formatError = error instanceof IllegalArgumentException
                        && error.getMessage() != null
                        && (error.getMessage().contains("wrong_api_format")
                        || error.getMessage().contains("unsupported")
                        || error.getMessage().contains("validation_error"));

                boolean recordAsFailure = !(error instanceof IllegalArgumentException) || formatError;
                if (recordAsFailure && outcomeRecorded.compareAndSet(false, true)) {
                    recordProviderFailure(model, error);
                    modelStatusService.updateStatus(model.getId(), new HealthCheckResult(
                            model.getId(), false, latency, java.time.Instant.now(), error.getMessage()));
                    telemetryTraceService.recordTrace(requester, model.getId(), latency, false, pipelineName);
                }

                boolean canFailOver = !emittedAnyData.get() && recordAsFailure;
                if (canFailOver) {
                    log.warn("[TxID: {}] Streaming error for model '{}' ({}ms): {}. Failing over to next candidate...",
                            transactionId, model.getId(), latency, error.getMessage());
                    return streamCandidate(candidates, candidateIndex + 1, requestBody, requester,
                            transactionId, pipelineName, estimatedTokens);
                }
                return Flux.error(error);
            });
        });
    }

    private boolean isProviderAvailable(Model model) {
        if (model == null || model.getProviderId() == null) {
            return false;
        }
        String unavailableReason = redisPersistenceService.getProviderUnavailableReason(model.getProviderId());
        return unavailableReason == null;
    }

    private boolean recordProviderFailure(Model model, Throwable error) {
        if (model == null || model.getProviderId() == null) {
            return false;
        }
        String providerId = model.getProviderId();

        if (error instanceof ProviderFailureException failure) {
            if (failure.isProviderWide()) {
                Duration cooldown = failure.getRetryAfter() != null && !failure.getRetryAfter().isZero()
                        ? failure.getRetryAfter()
                        : Duration.ofSeconds(routingProperties.getRateLimitCooldownSeconds());
                redisPersistenceService.setProviderUnavailable(providerId, failure.getMessage(), cooldown);
                log.warn("Provider '{}' experienced provider-wide failure ({}: {}). Setting cooldown for {}s.",
                        providerId, failure.getFailureType(), failure.getMessage(), cooldown.toSeconds());
                return true;
            }
            return false;
        }

        if (error instanceof LlmProviderClient.RateLimitException rateLimit) {
            Duration cooldown = Duration.ofSeconds(routingProperties.getRateLimitCooldownSeconds());
            redisPersistenceService.setProviderUnavailable(providerId, "Rate limit exceeded (429)", cooldown);
            log.warn("Provider '{}' rate-limited (429). Setting cooldown for {}s.", providerId, cooldown.toSeconds());
            return true;
        }

        if (error instanceof LlmProviderClient.UpstreamServiceException e) {
            // Isolate model-level overloads/timeouts from provider-level cooldowns.
            // A 503 (Overload) or 504 (Timeout) on one model should NOT bring down the entire provider.
            if (e.getStatusCode() == 503 || e.getStatusCode() == 504) {
                return false; // The model itself is marked DOWN, but the provider is spared.
            }

            int consecutive = redisPersistenceService.incrementProviderConsecutiveErrors(providerId);
            if (consecutive >= routingProperties.getConsecutiveErrorThreshold()) {
                Duration cooldown = Duration.ofSeconds(routingProperties.getProviderErrorCooldownSeconds());
                redisPersistenceService.setProviderUnavailable(providerId,
                        "Exceeded consecutive error threshold (" + consecutive + ")", cooldown);
                log.warn("Provider '{}' reached {} consecutive 5xx errors. Setting cooldown for {}s.",
                        providerId, consecutive, cooldown.toSeconds());
                return true;
            }
        }
        return false;
    }

    private void recordProviderSuccess(Model model) {
        if (model != null && model.getProviderId() != null) {
            redisPersistenceService.resetProviderConsecutiveErrors(model.getProviderId());
            redisPersistenceService.clearProviderUnavailable(model.getProviderId());
        }
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
     * Reset circuit breaker and availability state for a model and its provider.
     */
    public void resetCircuitBreaker(String modelId) {
        modelRegistry.getModel(modelId).ifPresent(model -> {
            redisPersistenceService.clearProviderUnavailable(model.getProviderId());
            redisPersistenceService.resetProviderConsecutiveErrors(model.getProviderId());
        });
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
                    case "medium", "med", "mid", "moderate" -> requestBody.put("reasoning_effort", "medium");
                    case "low", "min", "minimum", "minimal" -> requestBody.put("reasoning_effort", "low");
                    case "none", "off", "false", "disabled", "0" -> requestBody.put("reasoning_effort", "none");
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

    /**
     * Clamp max_tokens to a provider-specific minimum floor for real (routed) requests.
     * Some providers (e.g. explabs gpt-6-luna) reject max_tokens below a threshold with a
     * 400 error; if a client sends a tiny value, raise it to the configured floor instead of
     * letting the request fail. 0 / unset means no floor is applied.
     */
    private void applyMinMaxTokens(Map<String, Object> upstreamRequest, Model model) {
        LlmProvidersProperties.ProviderConfig config = modelRegistry.getProviderConfig(model.getProviderId());
        if (config == null || config.getMinMaxTokens() <= 0) return;
        int floor = config.getMinMaxTokens();
        Object current = upstreamRequest.get("max_tokens");
        if (current instanceof Number n && n.intValue() < floor) {
            upstreamRequest.put("max_tokens", floor);
            log.info("Clamped max_tokens from {} to {} for model '{}' (provider '{}' minimum)",
                    n.intValue(), floor, model.getId(), model.getProviderId());
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
        return 0;
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
}
