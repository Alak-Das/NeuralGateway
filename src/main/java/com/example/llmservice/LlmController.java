package com.example.llmservice;

import com.example.llmservice.domain.health.HealthCheckResult;
import com.example.llmservice.domain.ModelStatus;
import com.example.llmservice.domain.routing.RoutingScore;
import com.example.llmservice.service.LlmGatewayFacade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class LlmController {
    
    private final LlmGatewayFacade gatewayFacade;

    public LlmController(LlmGatewayFacade gatewayFacade) {
        this.gatewayFacade = gatewayFacade;
    }

    // ==========================================
    // Coding Pipeline
    // ==========================================

    @Operation(
        summary = "Generate coding completions",
        description = "OpenAI-compatible chat completions optimized for coding tasks. Routes to the fastest healthy coding model, normalizes IDE tool calls, and supports streaming (SSE).",
        tags = {"Coding Pipeline"}
    )
    @PostMapping("/coding/chat/completions")
    public ResponseEntity<?> generateCodingOpenAi(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "cline-proxy")
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return processRequest(httpRequest, request, requester, "coding");
    }

    @Hidden
    @PostMapping({"/coding/v1/chat/completions", "/v1/coding/chat/completions"})
    public ResponseEntity<?> generateCodingOpenAiAlias(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return generateCodingOpenAi(httpRequest, request, requester);
    }

    // ==========================================
    // Reasoning Pipeline
    // ==========================================

    @Operation(
        summary = "Generate reasoning completions",
        description = "OpenAI-compatible chat completions tuned for deep analytical reasoning, math, and architecture planning. Enforces context window checks and EMA latency routing.",
        tags = {"Reasoning Pipeline"}
    )
    @PostMapping("/reasoning/chat/completions")
    public ResponseEntity<?> generateReasoningOpenAi(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "cline-proxy")
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return processRequest(httpRequest, request, requester, "reasoning");
    }

    @Hidden
    @PostMapping({"/reasoning/v1/chat/completions", "/v1/reasoning/chat/completions"})
    public ResponseEntity<?> generateReasoningOpenAiAlias(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return generateReasoningOpenAi(httpRequest, request, requester);
    }

    // ==========================================
    // Vision Pipeline
    // ==========================================

    @Operation(
        summary = "Generate multimodal vision completions",
        description = "OpenAI-compatible multimodal chat completions for visual question answering and image inspection. Accepts base64 image data URLs and web image links.",
        tags = {"Vision Pipeline"}
    )
    @PostMapping("/vision/chat/completions")
    public ResponseEntity<?> generateVisionOpenAi(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "cline-proxy")
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return processRequest(httpRequest, request, requester, "vision");
    }

    @Hidden
    @PostMapping({"/vision/v1/chat/completions", "/v1/vision/chat/completions"})
    public ResponseEntity<?> generateVisionOpenAiAlias(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        return generateVisionOpenAi(httpRequest, request, requester);
    }

    // ==========================================
    // Fleet Health & Diagnostics
    // ==========================================

    @Operation(
        summary = "Get all model statuses",
        description = "Returns current operational status, EMA latency, active connections, total requests, and circuit breaker state across all registered models.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping("/models/status")
    public List<ModelStatus> getStatus() {
        return gatewayFacade.getModelStatuses();
    }

    @Operation(
        summary = "Stream model status updates",
        description = "Subscribes to a real-time Server-Sent Events (SSE) feed emitting status changes whenever background health checks finish.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping(value = "/models/status/stream", produces = "text/event-stream")
    public SseEmitter streamModelStatus() {
        return gatewayFacade.subscribeToStatusUpdates();
    }

    @Operation(
        summary = "Trigger on-demand health ping",
        description = "Sends an immediate health ping to a single model and updates its recorded status and latency in Redis.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/models/ping")
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
    @PostMapping("/models/circuit-reset")
    public void resetCircuitBreaker(
            @Parameter(description = "Exact name of model to reset", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        gatewayFacade.resetCircuitBreaker(model);
    }

    // ==========================================
    // Telemetry
    // ==========================================

    @Operation(
        summary = "Get requester usage breakdown",
        description = "Returns request count statistics grouped by calling client or agent (based on X-Requester header).",
        tags = {"Telemetry"}
    )
    @GetMapping("/requesters/status")
    public List<Map<String, Object>> getRequesterStatus() {
        return gatewayFacade.getRequesterTelemetry();
    }

    // ==========================================
    // Internal Helper & Formatting
    // ==========================================

    private ResponseEntity<?> processRequest(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            Map<String, Object> request,
            String requester,
            String pipeline) {
        String transactionId = java.util.UUID.randomUUID().toString();
        log.info("[TxID: {}] Received OpenAI-compatible {} proxy request to exact endpoint '{}' from '{}'",
                transactionId, pipeline, httpRequest.getRequestURI(), requester);

        long start = System.currentTimeMillis();
        Map<String, Object> response = gatewayFacade.processChatCompletion(request, requester, transactionId, pipeline);
        log.info("[TxID: {}] {} proxy request completed in {}ms", transactionId, pipeline, (System.currentTimeMillis() - start));

        return formatOpenAiResponse(request, response);
    }

    private ResponseEntity<?> formatOpenAiResponse(Map<String, Object> request, Map<String, Object> response) {
        if (Boolean.TRUE.equals(request.get("stream"))) {
            try {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                Map<String, Object> message = (choices != null && !choices.isEmpty()) ? (Map<String, Object>) choices.get(0).get("message") : null;
                ObjectMapper mapper = new ObjectMapper();
                
                // Chunk 1: Content & Tool Calls
                Map<String, Object> delta = new HashMap<>();
                delta.put("role", "assistant");
                if (message != null && message.containsKey("content")) {
                    delta.put("content", message.get("content"));
                }
                boolean hasToolCalls = false;
                if (message != null && message.containsKey("tool_calls")) {
                    List<Map<String, Object>> originalToolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                    if (originalToolCalls != null && !originalToolCalls.isEmpty()) {
                        hasToolCalls = true;
                        List<Map<String, Object>> streamToolCalls = new ArrayList<>();
                        for (int i = 0; i < originalToolCalls.size(); i++) {
                            Map<String, Object> tc = new HashMap<>(originalToolCalls.get(i));
                            tc.put("index", i);
                            
                            Map<String, Object> function = (Map<String, Object>) tc.get("function");
                            if (function != null && function.containsKey("arguments")) {
                                Object args = function.get("arguments");
                                if (!(args instanceof String)) {
                                    Map<String, Object> newFunction = new HashMap<>(function);
                                    newFunction.put("arguments", mapper.writeValueAsString(args));
                                    tc.put("function", newFunction);
                                }
                            }
                            
                            streamToolCalls.add(tc);
                        }
                        delta.put("tool_calls", streamToolCalls);
                    }
                }
                
                Map<String, Object> chunkChoice1 = new HashMap<>();
                chunkChoice1.put("index", 0);
                chunkChoice1.put("delta", delta);
                chunkChoice1.put("finish_reason", null);
                
                Map<String, Object> chunk1 = new HashMap<>();
                chunk1.put("id", response.get("id"));
                chunk1.put("object", "chat.completion.chunk");
                chunk1.put("created", response.get("created"));
                chunk1.put("model", response.get("model"));
                chunk1.put("choices", List.of(chunkChoice1));
                
                // Chunk 2: Finish Reason (tool_calls if tools called, otherwise stop)
                Map<String, Object> chunkChoice2 = new HashMap<>();
                chunkChoice2.put("index", 0);
                chunkChoice2.put("delta", new HashMap<>());
                chunkChoice2.put("finish_reason", hasToolCalls ? "tool_calls" : "stop");
                
                Map<String, Object> chunk2 = new HashMap<>(chunk1);
                chunk2.put("choices", List.of(chunkChoice2));
                
                String sse = "data: " + mapper.writeValueAsString(chunk1) + "\n\n" +
                             "data: " + mapper.writeValueAsString(chunk2) + "\n\n";
                             
                // Chunk 3: Usage (if requested)
                Map<String, Object> streamOptions = (Map<String, Object>) request.get("stream_options");
                if (streamOptions != null && Boolean.TRUE.equals(streamOptions.get("include_usage")) && response.containsKey("usage")) {
                    Map<String, Object> chunk3 = new HashMap<>(chunk1);
                    chunk3.put("choices", List.of()); // OpenAI usage chunks have empty choices
                    chunk3.put("usage", response.get("usage"));
                    sse += "data: " + mapper.writeValueAsString(chunk3) + "\n\n";
                }
                
                sse += "data: [DONE]\n\n";
                
                return ResponseEntity.ok()
                        .header("Content-Type", "text/event-stream")
                        .body(sse);
            } catch (Exception e) {
                log.error("Failed to convert to SSE chunk", e);
                return ResponseEntity.internalServerError().build();
            }
        }
        
        return ResponseEntity.ok(response);
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
}
