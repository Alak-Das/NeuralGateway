package com.alak.neuralgateway.service;

import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service responsible for intelligent pipeline and capability detection.
 * Resolves whether an incoming chat completion request should be dispatched
 * to CODING, REASONING, or VISION pipelines.
 */
@Slf4j
@Service
public class PipelineResolverService {

    private final ModelRegistry modelRegistry;

    private static final Set<String> CODING_AGENTS = Set.of(
            "cline", "roo", "cursor", "aider", "continue", "vscode", "copilot", "jetbrains", "pycharm", "intellij"
    );

    private static final Set<String> REASONING_AGENTS = Set.of(
            "arthascope", "analyzer", "reasoner", "evaluator"
    );

    private static final Set<String> CODING_TOOL_KEYWORDS = Set.of(
            "write_to_file", "replace_in_file", "read_file", "edit", "editor", "bash", "terminal",
            "execute_command", "git", "grep", "file_search", "patch", "compile", "run_test"
    );

    private static final Pattern CODE_INDICATOR_PATTERN = Pattern.compile(
            "(```[a-zA-Z0-9_-]*|\\bdef\\s+[a-zA-Z_]|\\bclass\\s+[a-zA-Z_]|\\bfunction\\s+[a-zA-Z_]|\\bpublic\\s+(static\\s+)?(void|class|interface)|\\bimport\\s+[a-zA-Z0-9_.]+|\\bpackage\\s+[a-zA-Z0-9_.]+)",
            Pattern.CASE_INSENSITIVE
    );

    public record ResolutionResult(Pipeline pipeline, String reason, String modelRequested) {}

    public PipelineResolverService(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * Resolves the target pipeline using our multi-tier decision engine.
     */
    public ResolutionResult resolve(Map<String, Object> requestBody,
                                    HttpServletRequest httpRequest,
                                    String requester,
                                    String explicitPipelineOverride) {
        // 1. Explicit pipeline override (e.g. from scoped endpoint path like /v1/coding/...)
        if (explicitPipelineOverride != null && !explicitPipelineOverride.isBlank()) {
            try {
                Pipeline p = Pipeline.valueOf(explicitPipelineOverride.trim().toUpperCase(Locale.ROOT));
                return new ResolutionResult(p, "Explicit scoped endpoint path", extractModel(requestBody));
            } catch (IllegalArgumentException ignored) {}
        }

        // 2. Explicit header: X-Pipeline
        if (httpRequest != null) {
            String headerPipeline = httpRequest.getHeader("X-Pipeline");
            if (headerPipeline != null && !headerPipeline.isBlank()) {
                try {
                    Pipeline p = Pipeline.valueOf(headerPipeline.trim().toUpperCase(Locale.ROOT));
                    return new ResolutionResult(p, "X-Pipeline header (" + headerPipeline + ")", extractModel(requestBody));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        String modelRequested = extractModel(requestBody);

        // 3. Virtual Model Name matching
        if (modelRequested != null) {
            String mLower = modelRequested.trim().toLowerCase(Locale.ROOT);
            if (mLower.equals("coding") || mLower.equals("neural-coding") || mLower.contains("coder") || mLower.startsWith("code")) {
                return new ResolutionResult(Pipeline.CODING, "Virtual model '" + modelRequested + "'", modelRequested);
            }
            if (mLower.equals("reasoning") || mLower.equals("neural-reasoning") || mLower.contains("reason") || mLower.startsWith("think")) {
                return new ResolutionResult(Pipeline.REASONING, "Virtual model '" + modelRequested + "'", modelRequested);
            }
            if (mLower.equals("vision") || mLower.equals("neural-vision") || mLower.contains("multimodal") || mLower.contains("image")) {
                return new ResolutionResult(Pipeline.VISION, "Virtual model '" + modelRequested + "'", modelRequested);
            }

            // If the model is a known physical model with ONLY 1 pipeline capability:
            if (modelRegistry != null && modelRegistry.isValidModel(modelRequested)) {
                Model model = modelRegistry.getModel(modelRequested).orElse(null);
                if (model != null && model.getPipelines() != null && model.getPipelines().size() == 1) {
                    Pipeline single = model.getPipelines().iterator().next();
                    return new ResolutionResult(single, "Single-capability model '" + modelRequested + "' (" + single + ")", modelRequested);
                }
            }
        }

        // 4. Payload Inspection: Check for Multimodal / Image Content (Strict guardrail)
        if (hasMultimodalContent(requestBody)) {
            return new ResolutionResult(Pipeline.VISION, "Multimodal image payload detected in messages", modelRequested);
        }

        // 5. Payload Inspection: Check for IDE / Coding Tools
        if (hasCodingTools(requestBody)) {
            return new ResolutionResult(Pipeline.CODING, "IDE/Coding tools defined in request", modelRequested);
        }

        // 6. Caller Metadata: Check X-Requester / User-Agent
        if (requester != null && !requester.isBlank()) {
            String reqLower = requester.toLowerCase(Locale.ROOT);
            for (String agent : CODING_AGENTS) {
                if (reqLower.contains(agent)) {
                    return new ResolutionResult(Pipeline.CODING, "Coding agent identified via requester (" + requester + ")", modelRequested);
                }
            }
            for (String agent : REASONING_AGENTS) {
                if (reqLower.contains(agent)) {
                    return new ResolutionResult(Pipeline.REASONING, "Reasoning agent identified via requester (" + requester + ")", modelRequested);
                }
            }
        }
        if (httpRequest != null) {
            String userAgent = httpRequest.getHeader("User-Agent");
            if (userAgent != null && !userAgent.isBlank()) {
                String uaLower = userAgent.toLowerCase(Locale.ROOT);
                for (String agent : CODING_AGENTS) {
                    if (uaLower.contains(agent)) {
                        return new ResolutionResult(Pipeline.CODING, "Coding tool identified via User-Agent (" + userAgent + ")", modelRequested);
                    }
                }
            }
        }

        // 7. Payload Inspection: Code Block / Code Signature in latest user prompt
        if (hasCodeIndicators(requestBody)) {
            return new ResolutionResult(Pipeline.CODING, "Code blocks or programming keywords detected in prompt", modelRequested);
        }

        // 8. Default fallback: REASONING (general analytical / chat)
        return new ResolutionResult(Pipeline.REASONING, "Default general pipeline", modelRequested);
    }

    private String extractModel(Map<String, Object> requestBody) {
        if (requestBody == null) return null;
        Object m = requestBody.get("model");
        return m instanceof String s ? s : null;
    }

    private boolean hasMultimodalContent(Map<String, Object> requestBody) {
        if (requestBody == null) return false;
        Object messagesObj = requestBody.get("messages");
        if (!(messagesObj instanceof List<?> messages)) return false;

        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) continue;
            
            // Check images array (Ollama style)
            Object images = msg.get("images");
            if (images instanceof List<?> imgList && !imgList.isEmpty()) {
                return true;
            }

            // Check content structure (OpenAI style)
            Object content = msg.get("content");
            if (content instanceof List<?> contentParts) {
                for (Object partObj : contentParts) {
                    if (!(partObj instanceof Map<?, ?> part)) continue;
                    Object type = part.get("type");
                    if ("image_url".equals(type) || "image".equals(type) || part.containsKey("image_url")) {
                        return true;
                    }
                }
            } else if (content instanceof String s) {
                if (s.contains("data:image/") && (s.contains(";base64,") || s.contains("data:image/png") || s.contains("data:image/jpeg"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasCodingTools(Map<String, Object> requestBody) {
        if (requestBody == null) return false;
        
        // Check tools
        Object toolsObj = requestBody.get("tools");
        if (toolsObj instanceof List<?> tools) {
            for (Object toolObj : tools) {
                if (toolObj instanceof Map<?, ?> tool) {
                    Object functionObj = tool.get("function");
                    if (functionObj instanceof Map<?, ?> fn) {
                        Object nameObj = fn.get("name");
                        if (nameObj instanceof String name && isCodingToolName(name)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Check legacy functions
        Object functionsObj = requestBody.get("functions");
        if (functionsObj instanceof List<?> functions) {
            for (Object fnObj : functions) {
                if (fnObj instanceof Map<?, ?> fn) {
                    Object nameObj = fn.get("name");
                    if (nameObj instanceof String name && isCodingToolName(name)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isCodingToolName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String kw : CODING_TOOL_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCodeIndicators(Map<String, Object> requestBody) {
        if (requestBody == null) return false;
        Object messagesObj = requestBody.get("messages");
        if (!(messagesObj instanceof List<?> messages) || messages.isEmpty()) return false;

        // Check the last user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object msgObj = messages.get(i);
            if (msgObj instanceof Map<?, ?> msg && "user".equals(msg.get("role"))) {
                Object contentObj = msg.get("content");
                if (contentObj instanceof String text) {
                    return CODE_INDICATOR_PATTERN.matcher(text).find();
                }
                break;
            }
        }
        return false;
    }
}
