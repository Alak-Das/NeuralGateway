package com.example.llmservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class PayloadTelemetryService {

    public enum PayloadLogMode {
        NONE,
        SUMMARY,
        TRUNCATED,
        FULL
    }

    private final PayloadLogMode logMode;
    private final int previewMaxChars;
    private final ObjectMapper mapper = new ObjectMapper();

    public PayloadTelemetryService(
            @Value("${llm.logging.payload-mode:SUMMARY}") String modeStr,
            @Value("${llm.logging.preview-max-chars:120}") int previewMaxChars) {
        PayloadLogMode mode;
        try {
            mode = PayloadLogMode.valueOf(modeStr != null ? modeStr.trim().toUpperCase() : "SUMMARY");
        } catch (Exception e) {
            mode = PayloadLogMode.SUMMARY;
        }
        this.logMode = mode;
        this.previewMaxChars = Math.max(20, previewMaxChars);
        log.info("Initialized PayloadTelemetryService with mode={} and previewMaxChars={}", this.logMode, this.previewMaxChars);
    }

    public PayloadLogMode getLogMode() {
        return logMode;
    }

    public String summarizeRequest(Map<String, Object> request) {
        if (request == null || logMode == PayloadLogMode.NONE) {
            return null;
        }

        if (logMode == PayloadLogMode.FULL) {
            try {
                return mapper.writeValueAsString(request);
            } catch (Exception e) {
                return request.toString();
            }
        }

        StringBuilder sb = new StringBuilder();

        // 1. Basic Model & Sampling Config
        Object model = request.get("model");
        sb.append("model=").append(model != null ? model : "unknown");

        if (request.containsKey("stream")) {
            sb.append(" | stream=").append(request.get("stream"));
        }
        if (request.containsKey("max_tokens")) {
            sb.append(" | max_tokens=").append(request.get("max_tokens"));
        }
        if (request.containsKey("temperature")) {
            sb.append(" | temp=").append(request.get("temperature"));
        }

        // 2. Messages Breakdown
        Object msgsObj = request.get("messages");
        if (msgsObj instanceof List<?> msgs) {
            int totalMsgs = msgs.size();
            int systemCount = 0;
            int userCount = 0;
            int assistantCount = 0;
            int toolCount = 0;
            int otherCount = 0;
            long totalCharLength = 0;
            String lastUserMsg = null;

            for (Object mObj : msgs) {
                if (!(mObj instanceof Map<?, ?> m)) continue;
                String role = (String) m.get("role");
                Object contentObj = m.get("content");
                String content = contentObj instanceof String s ? s : "";
                totalCharLength += content.length();

                if ("system".equalsIgnoreCase(role)) {
                    systemCount++;
                } else if ("user".equalsIgnoreCase(role)) {
                    userCount++;
                    lastUserMsg = content;
                } else if ("assistant".equalsIgnoreCase(role)) {
                    assistantCount++;
                } else if ("tool".equalsIgnoreCase(role)) {
                    toolCount++;
                } else {
                    otherCount++;
                }
            }

            sb.append(" | messages=").append(totalMsgs).append(" (");
            List<String> roleParts = new ArrayList<>();
            if (systemCount > 0) roleParts.add("system:" + systemCount);
            if (userCount > 0) roleParts.add("user:" + userCount);
            if (assistantCount > 0) roleParts.add("assistant:" + assistantCount);
            if (toolCount > 0) roleParts.add("tool:" + toolCount);
            if (otherCount > 0) roleParts.add("other:" + otherCount);
            sb.append(String.join(", ", roleParts));
            sb.append(" | ~").append(formatCharCount(totalCharLength)).append(" chars)");

            if (lastUserMsg != null && !lastUserMsg.isBlank()) {
                sb.append(" | lastUserMsg=\"").append(cleanSnippet(lastUserMsg, previewMaxChars)).append("\"");
            }
        }

        // 3. Registered Tools Inventory
        Object toolsObj = request.get("tools");
        if (toolsObj instanceof List<?> tools) {
            int toolCount = tools.size();
            List<String> toolNames = new ArrayList<>();
            for (Object tObj : tools) {
                if (tObj instanceof Map<?, ?> t && t.get("function") instanceof Map<?, ?> fn) {
                    Object name = fn.get("name");
                    if (name != null) {
                        toolNames.add(name.toString());
                    }
                }
            }

            sb.append(" | tools=").append(toolCount).append(" [");
            if (toolNames.size() <= 4) {
                sb.append(String.join(", ", toolNames));
            } else {
                sb.append(String.join(", ", toolNames.subList(0, 3)))
                  .append(", +").append(toolNames.size() - 3).append(" more");
            }
            sb.append("]");
        }

        return sb.toString();
    }

    public String summarizeToolCallArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }

        if (logMode == PayloadLogMode.FULL) {
            try {
                return mapper.writeValueAsString(args);
            } catch (Exception e) {
                return args.toString();
            }
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            String key = entry.getKey();
            Object val = entry.getValue();

            sb.append(key).append("=");
            if (val == null) {
                sb.append("null");
            } else if (val instanceof String s) {
                if (s.length() > 60 || s.contains("\n")) {
                    sb.append("\"").append(cleanSnippet(s, 50)).append("\" (").append(s.length()).append(" chars)");
                } else {
                    sb.append("\"").append(s).append("\"");
                }
            } else if (val instanceof List<?> list) {
                if (list.size() > 3) {
                    sb.append("[").append(list.get(0)).append(", +").append(list.size() - 1).append(" items]");
                } else {
                    sb.append(list.toString());
                }
            } else {
                sb.append(val.toString());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String cleanSnippet(String text, int maxLen) {
        if (text == null) return "";
        String singleLine = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (singleLine.length() <= maxLen) {
            return singleLine;
        }
        return singleLine.substring(0, maxLen) + "...";
    }

    private String formatCharCount(long chars) {
        if (chars >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", chars / 1_000_000.0);
        } else if (chars >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", chars / 1_000.0);
        }
        return String.valueOf(chars);
    }
}
