package com.alak.neuralgateway;

import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.service.LlmGatewayFacade;
import com.alak.neuralgateway.service.PipelineResolverService;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.tracing.Tracer;

@Slf4j
@RestController
@CrossOrigin(origins = "${neuralgateway.cors.allowed-origins:*}")
public class LlmController {

    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();
    
    private final LlmGatewayFacade gatewayFacade;
    private final PipelineResolverService pipelineResolver;
    private final com.alak.neuralgateway.service.ModelRegistry modelRegistry;
    private final com.alak.neuralgateway.service.TelemetryTraceService telemetryTraceService;
    private final Tracer tracer;

    public LlmController(LlmGatewayFacade gatewayFacade, Tracer tracer,
                         PipelineResolverService pipelineResolver, 
                         com.alak.neuralgateway.service.ModelRegistry modelRegistry,
                         com.alak.neuralgateway.service.TelemetryTraceService telemetryTraceService) {
        this.gatewayFacade = gatewayFacade;
        this.tracer = tracer;
        this.pipelineResolver = pipelineResolver;
        this.modelRegistry = modelRegistry;
        this.telemetryTraceService = telemetryTraceService;
    }

    // ==========================================
    // OpenAI Standard API (/v1)
    // ==========================================

    @Operation(
        summary = "Create chat completion",
        description = "Standard OpenAI-compatible chat completions endpoint (`/v1/chat/completions`). " +
                      "Intelligently routes requests to the optimal model based on virtual model alias (coding, reasoning, vision, auto), " +
                      "multimodal image payload, IDE/coding tool definitions, or caller identification (Cline, Cursor, etc.). " +
                      "Supports SSE streaming, tool calls normalization, automatic failover, and priority-weighted latency routing (score = (latency + connectionPenalty * activeConnections) / priority).",
        tags = {"OpenAI API"}
    )
    @PostMapping("/v1/chat/completions")
    public StreamingResponseBody generateChatCompletion(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "Cline")
            @RequestHeader(value = "X-Requester", defaultValue = "Anonymous") String requester) {
        var resolution = pipelineResolver.resolve(request, httpRequest, requester, null);
        return processRequest(httpRequest, httpResponse, request, requester, resolution.pipeline().name().toLowerCase(), resolution.reason());
    }

    @Operation(
        summary = "List models",
        description = "Standard OpenAI-compatible model listing endpoint (`/v1/models`). " +
                      "Returns virtual routing aliases (coding, reasoning, vision, auto) as well as all registered physical models in the fleet.",
        tags = {"OpenAI API"}
    )
    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        List<ModelStatus> statuses = gatewayFacade.getAllModelStatuses();
        List<Map<String, Object>> modelsData = new ArrayList<>();
        long createdTimestamp = System.currentTimeMillis() / 1000;

        // Virtual model aliases for standard OpenAI clients
        modelsData.add(createModelObject("coding", createdTimestamp));
        modelsData.add(createModelObject("neural-coding", createdTimestamp));
        modelsData.add(createModelObject("reasoning", createdTimestamp));
        modelsData.add(createModelObject("neural-reasoning", createdTimestamp));
        modelsData.add(createModelObject("vision", createdTimestamp));
        modelsData.add(createModelObject("neural-vision", createdTimestamp));
        modelsData.add(createModelObject("auto", createdTimestamp));
        modelsData.add(createModelObject("neural-gateway", createdTimestamp));

        // Physical fleet models
        for (ModelStatus status : statuses) {
            modelsData.add(createModelObject(status.model(), createdTimestamp));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("object", "list");
        response.put("data", modelsData);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Retrieve model",
        description = "Standard OpenAI-compatible single model retrieval endpoint (`/v1/models/{modelId}`).",
        tags = {"OpenAI API"}
    )
    @GetMapping("/v1/models/{modelId}")
    public ResponseEntity<?> getModelDetails(
            @Parameter(description = "ID of the model to retrieve", example = "coding")
            @PathVariable String modelId) {
        long createdTimestamp = System.currentTimeMillis() / 1000;
        return ResponseEntity.ok(createModelObject(modelId, createdTimestamp));
    }

    private Map<String, Object> createModelObject(String id, long createdTimestamp) {
        Map<String, Object> model = new HashMap<>();
        model.put("id", id);
        model.put("object", "model");
        model.put("created", createdTimestamp);
        model.put("owned_by", "neural-gateway");
        return model;
    }

    // ==========================================
    // Fleet Health & Diagnostics (/api)
    // ==========================================

    @Operation(
        summary = "Get all model statuses",
        description = "Returns current operational status, latency, active connections, total requests, and circuit breaker state across all registered models.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping("/api/models/status")
    public List<ModelStatus> getStatus() {
        return gatewayFacade.getAllModelStatuses();
    }

    @Operation(
        summary = "Stream model status updates",
        description = "Subscribes to a real-time Server-Sent Events (SSE) feed emitting status changes whenever background health checks finish.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping(value = "/api/models/status/stream", produces = "text/event-stream")
    public SseEmitter streamModelStatus() {
        return gatewayFacade.subscribeToStatusUpdates();
    }

    @Operation(
        summary = "Trigger on-demand health ping",
        description = "Sends an immediate health ping to a single model and updates its recorded status and latency in Redis.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/ping")
    public HealthCheckResult pingModel(
            @Parameter(description = "Exact name of model to ping", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        return gatewayFacade.pingModel(model);
    }

    @Operation(
        summary = "Reset model error status",
        description = "Manually resets recorded errors, latency, and provider cooldown for the specified model, restoring it to active routing.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/circuit-reset")
    public void resetCircuitBreaker(
            @Parameter(description = "Exact name of model to reset", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        gatewayFacade.resetCircuitBreaker(model);
    }

    @Operation(
        summary = "Update model configuration",
        description = "Dynamically updates model configuration such as enabled status, priority, and pipelines at runtime.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/config")
    public ResponseEntity<Void> updateModelConfig(
            @Parameter(description = "ID of the model to update (optional if present in body)")
            @RequestParam(required = false) String model,
            @RequestBody Map<String, Object> config) {
        String targetModel = model;
        if (targetModel == null || targetModel.isBlank()) {
            targetModel = (String) config.get("model");
        }
        if (targetModel == null || targetModel.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        gatewayFacade.updateModelConfig(targetModel, config);
        return ResponseEntity.ok().build();
    }

    @Operation(
        summary = "Update model configuration by path",
        description = "Dynamically updates model configuration when model ID is included in path (e.g. /api/models/moonshotai/kimi-k3/config).",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/{*modelPath}")
    public ResponseEntity<Void> updateModelConfigByPath(
            @Parameter(description = "Path containing model ID (e.g. moonshotai/kimi-k3/config)")
            @PathVariable String modelPath,
            @RequestBody Map<String, Object> config) {
        String targetModel = modelPath != null ? modelPath.trim() : "";
        if (targetModel.startsWith("/")) {
            targetModel = targetModel.substring(1);
        }
        if (targetModel.endsWith("/config")) {
            targetModel = targetModel.substring(0, targetModel.length() - "/config".length());
        }
        if (targetModel.isBlank() || "ping".equalsIgnoreCase(targetModel)
                || "circuit-reset".equalsIgnoreCase(targetModel)
                || "config".equalsIgnoreCase(targetModel)
                || "status".equalsIgnoreCase(targetModel)) {
            return ResponseEntity.notFound().build();
        }
        gatewayFacade.updateModelConfig(targetModel, config);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // Telemetry (/api)
    // ==========================================

    @Operation(
        summary = "Get requester statistics",
        description = "Returns request count statistics grouped by calling client or agent (based on X-Requester header).",
        tags = {"Telemetry"}
    )
    @GetMapping("/api/requesters/status")
    public List<Map<String, Object>> getRequesterStatus() {
        return gatewayFacade.getRequesterTelemetry();
    }

    @Operation(
        summary = "Get requester historical telemetry",
        description = "Returns daily request count statistics for a specific requester.",
        tags = {"Telemetry"}
    )
    @GetMapping("/api/requesters/{requester}/history")
    public List<Map<String, Object>> getRequesterHistory(
            @PathVariable String requester,
            @RequestParam(defaultValue = "14") int days) {
        return gatewayFacade.getRequesterHistory(requester, days);
    }

    @Operation(
        summary = "Get live traces",
        description = "Returns the latest live request traces for the dashboard.",
        tags = {"Telemetry"}
    )
    @GetMapping("/api/telemetry/traces")
    public List<com.alak.neuralgateway.service.TelemetryTraceService.TraceLog> getLiveTraces() {
        return telemetryTraceService.getLatestTraces();
    }

    // ==========================================
    // Internal Helper & Formatting
    // ==========================================

    private StreamingResponseBody processRequest(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            Map<String, Object> request,
            String requester,
            String pipeline,
            String resolutionReason) {
        String transactionId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : UUID.randomUUID().toString();
        
        boolean streaming = Boolean.TRUE.equals(request.get("stream"));
        httpResponse.setContentType(streaming ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("X-Trace-Id", transactionId);
        return outputStream -> {
            org.slf4j.MDC.put("traceId", transactionId);
            org.slf4j.MDC.put("requester", requester);
            try {
                log.info("Received OpenAI-compatible {} proxy request to '{}' [Resolution: {}]",
                        pipeline, httpRequest.getRequestURI(), resolutionReason != null ? resolutionReason : "Direct");

                if (streaming) {
                Flux<String> upstreamEvents = gatewayFacade.processStreamingChatCompletion(
                        request, requester, pipeline);
                    writeStreamingResponse(outputStream, upstreamEvents, requester);
                    return;
                }

                long start = System.currentTimeMillis();
                Map<String, Object> response = gatewayFacade.processChatCompletion(request, requester, pipeline);
                log.info("{} proxy request completed in {}ms", pipeline, (System.currentTimeMillis() - start));
                STREAM_MAPPER.writeValue(outputStream, response);
            } catch (Exception e) {
                if (isClientDisconnect(e)) {
                    log.debug("Client connection disconnected prematurely: {}", e.getMessage());
                    return;
                }
                boolean timeoutOrInterrupted = isInterrupted(e) || isTimeout(e);
                if (timeoutOrInterrupted) {
                    log.warn("Gateway request timed out or interrupted: {}", e.getMessage());
                } else {
                    log.error("Gateway error: {}", e.getMessage(), e);
                }
                int status = timeoutOrInterrupted ? 504 : (e instanceof IllegalArgumentException ? 400 : 503);
                httpResponse.setStatus(status);
                String errorMsg = timeoutOrInterrupted
                        ? "Gateway timeout: Upstream LLM provider did not respond in time. Please retry."
                        : (e.getMessage() != null ? e.getMessage() : "Gateway error");
                String errorCode = timeoutOrInterrupted
                        ? "gateway_timeout"
                        : (e instanceof IllegalArgumentException ? "invalid_request" : "pipeline_exhausted");
                String errorType = timeoutOrInterrupted
                        ? "upstream_error"
                        : (e instanceof IllegalArgumentException ? "invalid_request_error" : "server_error");
                Map<String, Object> error = Map.of(
                        "message", errorMsg,
                        "type", errorType,
                        "code", errorCode);
                if (streaming) {
                    writeSseData(outputStream, STREAM_MAPPER.writeValueAsString(Map.of("error", error)));
                    writeSseData(outputStream, "[DONE]");
                } else {
                    STREAM_MAPPER.writeValue(outputStream, Map.of("error", error));
                }
            } finally {
                org.slf4j.MDC.clear();
            }
        };

    }

    private void writeStreamingResponse(OutputStream outputStream,
                                        Flux<String> upstreamEvents,
                                        String requester) throws IOException {
        AtomicBoolean doneSent = new AtomicBoolean(false);
        try {
            upstreamEvents
                    .doOnNext(event -> {
                        try {
                            writeSseData(outputStream, event);
                            if ("[DONE]".equals(event)) doneSent.set(true);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .blockLast();

            if (!doneSent.get()) writeSseData(outputStream, "[DONE]");
        } catch (Exception e) {
            if (isClientDisconnect(e)) {
                log.debug("Client connection disconnected prematurely: {}", e.getMessage());
                return;
            }
            boolean timeoutOrInterrupted = isInterrupted(e) || isTimeout(e);
            if (timeoutOrInterrupted) {
                log.warn("Streaming response timed out or interrupted for requester '{}': {}", requester, e.getMessage());
            } else {
                log.error("Streaming response failed: {}", e.getMessage(), e);
            }
            if (!doneSent.get()) {
                try {
                    String errorMsg = timeoutOrInterrupted
                            ? "Gateway timeout: Upstream LLM provider did not respond in time. Please retry."
                            : (e.getMessage() != null ? e.getMessage() : "Streaming upstream error");
                    String errorCode = timeoutOrInterrupted ? "gateway_timeout" : "pipeline_exhausted";
                    String errorType = timeoutOrInterrupted ? "upstream_error" : "server_error";
                    Map<String, Object> error = Map.of("error", Map.of(
                            "message", errorMsg,
                            "type", errorType,
                            "code", errorCode));
                    writeSseData(outputStream, STREAM_MAPPER.writeValueAsString(error));
                    writeSseData(outputStream, "[DONE]");
                } catch (Exception writeError) {
                    log.debug("Unable to send streaming error to disconnected client: {}", writeError.getMessage());
                }
            }
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private void writeSseData(OutputStream outputStream, String data) throws IOException {
        for (String line : data.split("\\R", -1)) {
            outputStream.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientResponseException.class)
    public ResponseEntity<?> handleWebClientResponseException(org.springframework.web.reactive.function.client.WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String message = (body != null && !body.isBlank()) ? body : ("Upstream API error: " + e.getStatusCode());
        Map<String, Object> error = Map.of(
            "message", message,
            "type", e.getStatusCode().is4xxClientError() ? "invalid_request_error" : "upstream_error",
            "code", String.valueOf(e.getStatusCode().value())
        );
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public ResponseEntity<?> handleAsyncTimeout(org.springframework.web.context.request.async.AsyncRequestTimeoutException e) {
        log.warn("Async request timed out â€” upstream LLM provider did not respond in time");
        Map<String, Object> error = Map.of(
            "message", "Upstream LLM provider did not respond in time. The request may have been too large or the model is warming up. Please retry.",
            "type", "upstream_error",
            "code", "gateway_timeout"
        );
        return ResponseEntity.status(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralException(Exception e) {
        if (isClientDisconnect(e)) {
            log.debug("Client connection disconnected prematurely: {}", e.getMessage());
            return null;
        }

        log.error("Gateway error: {}", e.getMessage(), e);
        if (e instanceof IllegalArgumentException) {
            Map<String, Object> error = Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "Invalid request",
                "type", "invalid_request_error",
                "code", "invalid_request"
            );
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", error));
        }
        Map<String, Object> error = Map.of(
            "message", e.getMessage() != null ? e.getMessage() : "Internal Gateway Error",
            "type", "server_error",
            "code", "pipeline_exhausted"
        );
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    private boolean isClientDisconnect(Throwable t) {
        if (t == null) return false;
        if (t instanceof java.net.SocketTimeoutException) return true;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer")
                || msg.contains("Response not usable") || msg.contains("Stream closed"))) {
            return true;
        }
        String className = t.getClass().getSimpleName();
        if (className.contains("ClientAbortException") || className.contains("AsyncRequestNotUsableException")) {
            return true;
        }
        return isClientDisconnect(t.getCause());
    }

    private boolean isInterrupted(Throwable t) {
        if (t == null) return false;
        if (t instanceof InterruptedException) return true;
        String msg = t.getMessage();
        if (msg != null && msg.contains("InterruptedException")) return true;
        return isInterrupted(t.getCause());
    }

    private boolean isTimeout(Throwable t) {
        if (t == null) return false;
        if (t instanceof java.util.concurrent.TimeoutException) return true;
        if (t instanceof io.netty.handler.timeout.ReadTimeoutException) return true;
        if (t instanceof io.netty.handler.timeout.TimeoutException) return true;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("TimeoutException") || msg.contains("timed out")
                || msg.contains("timeout") || msg.contains("within "))) {
            return true;
        }
        return isTimeout(t.getCause());
    }
}

