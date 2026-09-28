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

@Slf4j
@RestController
@CrossOrigin(origins = "*")
public class LlmController {

    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();
    
    private final LlmGatewayFacade gatewayFacade;
    private final PipelineResolverService pipelineResolver;

    public LlmController(LlmGatewayFacade gatewayFacade, PipelineResolverService pipelineResolver) {
        this.gatewayFacade = gatewayFacade;
        this.pipelineResolver = pipelineResolver;
    }

    // ==========================================
    // OpenAI Standard API (/v1)
    // ==========================================

    @Operation(
        summary = "Create chat completion",
        description = "Standard OpenAI-compatible chat completions endpoint (`/v1/chat/completions`). " +
                      "Intelligently routes requests to the optimal model based on virtual model alias (coding, reasoning, vision, auto), " +
                      "multimodal image payload, IDE/coding tool definitions, or caller identification (Cline, Cursor, etc.). " +
                      "Supports SSE streaming, tool calls normalization, automatic failover, and EMA latency routing.",
        tags = {"OpenAI API"}
    )
    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> generateChatCompletion(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "Cline")
            @RequestHeader(value = "X-Requester", defaultValue = "Anonymous") String requester) {
        var resolution = pipelineResolver.resolve(request, httpRequest, requester, null);
        return processRequest(httpRequest, request, requester, resolution.pipeline().name().toLowerCase(), resolution.reason());
    }

    @Operation(
        summary = "List models",
        description = "Standard OpenAI-compatible model listing endpoint (`/v1/models`). " +
                      "Returns virtual routing aliases (coding, reasoning, vision, auto) as well as all registered physical models in the fleet.",
        tags = {"OpenAI API"}
    )
    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        List<ModelStatus> statuses = gatewayFacade.getModelStatuses();
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
        description = "Returns current operational status, EMA latency, active connections, total requests, and circuit breaker state across all registered models.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping("/api/models/status")
    public List<ModelStatus> getStatus() {
        return gatewayFacade.getModelStatuses();
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
        summary = "Reset circuit breaker",
        description = "Manually closes a tripped circuit breaker for the specified model, restoring it to active routing.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/circuit-reset")
    public void resetCircuitBreaker(
            @Parameter(description = "Exact name of model to reset", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        gatewayFacade.resetCircuitBreaker(model);
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

    // ==========================================
    // Internal Helper & Formatting
    // ==========================================

    private ResponseEntity<?> processRequest(
            HttpServletRequest httpRequest,
            Map<String, Object> request,
            String requester,
            String pipeline,
            String resolutionReason) {
        String transactionId = UUID.randomUUID().toString();
        
        org.slf4j.MDC.put("txId", transactionId);
        org.slf4j.MDC.put("requester", requester);
        
        try {
            log.info("Received OpenAI-compatible {} proxy request to '{}' [Resolution: {}]",
                    pipeline, httpRequest.getRequestURI(), resolutionReason != null ? resolutionReason : "Direct");

            if (Boolean.TRUE.equals(request.get("stream"))) {
                Flux<String> upstreamEvents = gatewayFacade.processStreamingChatCompletion(
                        request, requester, transactionId, pipeline);
                StreamingResponseBody body = outputStream -> writeStreamingResponse(
                        outputStream, upstreamEvents, requester, transactionId);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .header("Cache-Control", "no-cache")
                        .header("X-Transaction-Id", transactionId)
                        .body(body);
            }

            long start = System.currentTimeMillis();
            Map<String, Object> response = gatewayFacade.processChatCompletion(request, requester, transactionId, pipeline);
            log.info("{} proxy request completed in {}ms", pipeline, (System.currentTimeMillis() - start));

            return formatOpenAiResponse(response, transactionId);
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private void writeStreamingResponse(OutputStream outputStream,
                                        Flux<String> upstreamEvents,
                                        String requester,
                                        String transactionId) throws IOException {
        org.slf4j.MDC.put("txId", transactionId);
        org.slf4j.MDC.put("requester", requester);
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
            log.error("Streaming response failed: {}", e.getMessage(), e);
            if (!doneSent.get()) {
                try {
                    Map<String, Object> error = Map.of("error", Map.of(
                            "message", e.getMessage() != null ? e.getMessage() : "Streaming upstream error",
                            "type", "upstream_error",
                            "code", "model_unavailable"));
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

    private ResponseEntity<?> formatOpenAiResponse(Map<String, Object> response, String transactionId) {
        return ResponseEntity.ok()
                .header("X-Transaction-Id", transactionId)
                .body(response);
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralException(Exception e) {
        if (isClientDisconnect(e)) {
            log.debug("Client connection disconnected prematurely: {}", e.getMessage());
            return null;
        }

        log.error("Gateway error: {}", e.getMessage(), e);
        Map<String, Object> error = Map.of(
            "message", e.getMessage() != null ? e.getMessage() : "Internal Gateway Error",
            "type", "gateway_error",
            "code", "model_unavailable"
        );
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    private boolean isClientDisconnect(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer"))) {
            return true;
        }
        String className = t.getClass().getSimpleName();
        if (className.contains("ClientAbortException") || className.contains("AsyncRequestNotUsableException")) {
            return true;
        }
        return isClientDisconnect(t.getCause());
    }
}
