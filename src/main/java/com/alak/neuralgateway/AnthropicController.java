package com.alak.neuralgateway;

import com.alak.neuralgateway.service.AnthropicAdapterService;
import com.alak.neuralgateway.service.LlmGatewayFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmGatewayFacade gatewayFacade;
    private final AnthropicAdapterService anthropicAdapterService;
    private final Tracer tracer;

    public AnthropicController(LlmGatewayFacade gatewayFacade,
                               AnthropicAdapterService anthropicAdapterService,
                               Tracer tracer) {
        this.gatewayFacade = gatewayFacade;
        this.anthropicAdapterService = anthropicAdapterService;
        this.tracer = tracer;
    }

    @PostMapping(value = "/v1/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object createMessage(
            @RequestBody Map<String, Object> anthropicReq,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String transactionId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : UUID.randomUUID().toString();
        String requester = httpRequest.getHeader("X-Requester");
        if (requester == null || requester.isBlank()) { requester = "Claude Code"; } final String finalRequester = requester;
        
        MDC.put("traceId", transactionId);
        MDC.put("requester", finalRequester);

        boolean isStream = anthropicReq.containsKey("stream") && Boolean.TRUE.equals(anthropicReq.get("stream"));
        String modelId = (String) anthropicReq.get("model");

        try {
            log.info("Received Anthropic API proxy request to '/v1/messages' [Model: {}]", modelId);
            
            // Translate request to OpenAI format
            Map<String, Object> openAiReq = anthropicAdapterService.convertToOpenAiRequest(anthropicReq);
            
            if (isStream) {
                httpResponse.setContentType("text/event-stream");
                httpResponse.setCharacterEncoding("UTF-8");
                httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
                httpResponse.setHeader("X-Trace-Id", transactionId);

                return (StreamingResponseBody) outputStream -> {
                    MDC.put("traceId", transactionId);
                    MDC.put("requester", finalRequester);
                    try {
                        long startTime = System.currentTimeMillis();
                        String pipelineName = ("reasoning".equalsIgnoreCase(modelId)) ? "REASONING" : "CODING"; Flux<String> openAiStream = gatewayFacade.processStreamingChatCompletion(openAiReq, finalRequester, pipelineName);
                        Flux<String> anthropicStream = anthropicAdapterService.convertToAnthropicStream(openAiStream, modelId);
                        
                        writeStreamingResponse(outputStream, anthropicStream, finalRequester);
                        log.info("Anthropic proxy streaming request completed in {}ms", System.currentTimeMillis() - startTime);
                    } catch (Exception e) {
                        log.error("Error in Anthropic streaming response", e);
                        writeError(outputStream, "server_error", "Streaming failed: " + e.getMessage(), 500);
                    } finally {
                        MDC.clear();
                    }
                };
            } else {
                String pipelineName = ("reasoning".equalsIgnoreCase(modelId)) ? "REASONING" : "CODING"; Map<String, Object> openAiResp = gatewayFacade.processChatCompletion(openAiReq, finalRequester, pipelineName);
                Map<String, Object> anthropicResp = anthropicAdapterService.convertToAnthropicResponse(openAiResp);
                httpResponse.setHeader("X-Trace-Id", transactionId);
                return anthropicResp;
            }
        } catch (Exception e) {
            log.error("Anthropic proxy request failed", e);
            httpResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return Map.of("error", Map.of("type", "api_error", "message", e.getMessage()));
        } finally {
            if (!isStream) {
                MDC.clear();
            }
        }
    }

    private void writeStreamingResponse(OutputStream outputStream, Flux<String> anthropicStream, String requester) throws IOException {
        AtomicBoolean stopSent = new AtomicBoolean(false);
        try {
            anthropicStream
                .doOnNext(eventBlock -> {
                    try {
                        outputStream.write((eventBlock + "\n\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                        if (eventBlock.contains("message_stop")) {
                            stopSent.set(true);
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .blockLast();
        } catch (Exception e) {
            log.error("Streaming error: {}", e.getMessage());
            if (!stopSent.get()) {
                writeErrorEvent(outputStream, "server_error", e.getMessage());
            }
        }
    }
    
    private void writeError(OutputStream outputStream, String type, String message, int code) {
        try {
            String err = MAPPER.writeValueAsString(Map.of("error", Map.of("type", type, "message", message, "code", code)));
            outputStream.write(("event: error\ndata: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception e) {
            // ignore
        }
    }

    private void writeErrorEvent(OutputStream outputStream, String type, String message) {
        try {
            String err = MAPPER.writeValueAsString(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
            outputStream.write(("event: error\ndata: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception e) {
            // ignore
        }
    }
}



