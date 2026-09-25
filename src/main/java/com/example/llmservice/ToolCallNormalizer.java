package com.example.llmservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ToolCallNormalizer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadTelemetryService payloadTelemetryService;

    private static final Pattern ENV_WORKDIR_PATTERN = Pattern.compile("Working Directory:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_PATH_PATTERN = Pattern.compile("([A-Za-z]:[/\\\\][^\r\n\"']+)");

    public ToolCallNormalizer() {
        this(new PayloadTelemetryService("SUMMARY", 120));
    }

    public ToolCallNormalizer(PayloadTelemetryService payloadTelemetryService) {
        this.payloadTelemetryService = payloadTelemetryService != null ? payloadTelemetryService : new PayloadTelemetryService("SUMMARY", 120);
    }

    public void normalizeToolCalls(Map<String, Object> response, Map<String, Object> request, String transactionId) {
        if (response == null || !response.containsKey("choices")) {
            return;
        }

        String workingDirectory = extractWorkingDirectory(request);
        Map<String, Map<String, Object>> toolSchemaMap = extractToolSchemas(request);

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> rawChoices)) {
            return;
        }

        for (Object choiceObj : rawChoices) {
            if (!(choiceObj instanceof Map<?, ?> rawChoice)) continue;
            Map<String, Object> choice = (Map<String, Object>) rawChoice;

            Object msgObj = choice.get("message");
            if (!(msgObj instanceof Map<?, ?> rawMsg)) continue;
            Map<String, Object> message = (Map<String, Object>) rawMsg;

            Object toolCallsObj = message.get("tool_calls");
            if (toolCallsObj instanceof List<?> rawToolCalls && !rawToolCalls.isEmpty()) {
                choice.put("finish_reason", "tool_calls");

                for (Object tcObj : rawToolCalls) {
                    if (tcObj instanceof Map<?, ?> rawTc) {
                        Map<String, Object> tc = (Map<String, Object>) rawTc;
                        normalizeSingleToolCall(tc, toolSchemaMap, workingDirectory, transactionId);
                    }
                }
            }
        }
    }

    public void normalizeSingleToolCall(Map<String, Object> tc, Map<String, Map<String, Object>> toolSchemaMap, String workingDirectory, String transactionId) {
        Object fnObj = tc.get("function");
        if (!(fnObj instanceof Map<?, ?> rawFn)) return;
        Map<String, Object> function = (Map<String, Object>) rawFn;

        String toolName = (String) function.get("name");
        Object argsObj = function.get("arguments");

        Map<String, Object> argsMap = parseArguments(argsObj);
        if (argsMap == null) return;

        Map<String, Object> toolDef = toolSchemaMap != null ? toolSchemaMap.get(toolName) : null;
        Map<String, Object> properties = null;
        boolean additionalPropertiesAllowed = true;

        if (toolDef != null && toolDef.containsKey("parameters")) {
            Object paramsObj = toolDef.get("parameters");
            if (paramsObj instanceof Map<?, ?> params) {
                if (params.containsKey("properties") && params.get("properties") instanceof Map<?, ?> props) {
                    properties = (Map<String, Object>) props;
                }
                if (Boolean.FALSE.equals(params.get("additionalProperties"))) {
                    additionalPropertiesAllowed = false;
                }
            }
        }

        Map<String, Object> normalized = normalizeArgumentsMap(toolName, argsMap, properties, additionalPropertiesAllowed, workingDirectory);

        try {
            String jsonStr = mapper.writeValueAsString(normalized);
            function.put("arguments", jsonStr);
            log.info("[TxID: {}] Normalized tool call '{}': {}", transactionId, toolName, payloadTelemetryService.summarizeToolCallArgs(normalized));
        } catch (Exception e) {
            log.warn("[TxID: {}] Failed to serialize normalized arguments for tool '{}': {}", transactionId, toolName, e.getMessage());
        }
    }

    public Map<String, Object> normalizeArgumentsMap(
            String toolName,
            Map<String, Object> originalArgs,
            Map<String, Object> expectedProperties,
            boolean additionalPropertiesAllowed,
            String workingDirectory) {

        Map<String, Object> result = new LinkedHashMap<>(originalArgs);

        if ("editor".equals(toolName)) {
            // 1. Path handling
            copyIfMissing(result, "path", "filePath", "file_path", "pathInProject", "path_in_project", "target_file", "targetFile", "file");
            if (result.containsKey("path") && result.get("path") instanceof String p) {
                result.put("path", resolveAbsolutePath(p, workingDirectory));
            }

            // 2. new_text handling (CRITICAL: fixes "Invalid input: expected string, received undefined -> at new_text")
            copyIfMissing(result, "new_text", "newText", "text", "content", "new_content", "newContent", "replacement");

            // 3. old_text handling (fixes edit being treated as file creation)
            copyIfMissing(result, "old_text", "oldText", "original_text", "originalText", "targetContent", "target_content", "target_text", "targetText");

            // 4. insert_line handling
            copyIfMissing(result, "insert_line", "insertLine", "line", "lineNumber", "line_number");
            if (result.containsKey("insert_line") && result.get("insert_line") instanceof String s) {
                try {
                    result.put("insert_line", Integer.parseInt(s.trim()));
                } catch (Exception ignored) {}
            }

            // Clean up unaccepted keys so strict schema doesn't fail
            Set<String> validKeys = Set.of("path", "old_text", "new_text", "insert_line");
            result.keySet().removeIf(k -> !validKeys.contains(k));
            return result;
        }

        if ("jetbrains-idea__replace_text_in_file".equals(toolName)) {
            copyIfMissing(result, "pathInProject", "path", "filePath", "file_path", "file");
            if (result.containsKey("pathInProject") && result.get("pathInProject") instanceof String p) {
                result.put("pathInProject", toRelativePath(p, workingDirectory));
            }
            copyIfMissing(result, "oldText", "old_text", "original_text", "originalText", "targetContent", "target_content");
            copyIfMissing(result, "newText", "new_text", "text", "content", "new_content", "newContent");
            copyIfMissing(result, "replaceAll", "replace_all");
            copyIfMissing(result, "caseSensitive", "case_sensitive");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__create_new_file".equals(toolName)) {
            copyIfMissing(result, "pathInProject", "path", "filePath", "file_path", "file");
            if (result.containsKey("pathInProject") && result.get("pathInProject") instanceof String p) {
                result.put("pathInProject", toRelativePath(p, workingDirectory));
            }
            copyIfMissing(result, "text", "new_text", "newText", "content", "new_content");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__get_file_text_by_path".equals(toolName)) {
            copyIfMissing(result, "pathInProject", "path", "filePath", "file_path", "file");
            if (result.containsKey("pathInProject") && result.get("pathInProject") instanceof String p) {
                result.put("pathInProject", toRelativePath(p, workingDirectory));
            }
            copyIfMissing(result, "truncateMode", "truncate_mode");
            copyIfMissing(result, "maxLinesCount", "max_lines_count");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__get_file_problems".equals(toolName)) {
            copyIfMissing(result, "filePath", "path", "pathInProject", "file_path", "file");
            copyIfMissing(result, "errorsOnly", "errors_only");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__open_file_in_editor".equals(toolName)) {
            copyIfMissing(result, "filePath", "path", "pathInProject", "file_path", "file");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__reformat_file".equals(toolName)) {
            copyIfMissing(result, "path", "filePath", "pathInProject", "file_path", "file");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__list_directory_tree".equals(toolName)) {
            copyIfMissing(result, "directoryPath", "path", "directory", "dirPath", "dir_path");
            copyIfMissing(result, "maxDepth", "max_depth");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__rename_refactoring".equals(toolName)) {
            copyIfMissing(result, "pathInProject", "path", "filePath", "file_path");
            copyIfMissing(result, "symbolName", "symbol_name");
            copyIfMissing(result, "newName", "new_name");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("jetbrains-idea__runNotebookCell".equals(toolName)) {
            copyIfMissing(result, "file_path", "filePath", "path");
            copyIfMissing(result, "cell_id", "cellId");
            copyIfMissing(result, "projectPath", "project_path");
        } else if ("run_commands".equals(toolName)) {
            ensureStringArray(result, "commands", "command", "cmd");
        } else if ("read_files".equals(toolName)) {
            ensureStringArray(result, "files", "file", "path", "filePath", "paths");
        } else if ("search_codebase".equals(toolName)) {
            ensureStringArray(result, "queries", "query");
        }

        // Generic Schema-driven property matching
        if (expectedProperties != null && !expectedProperties.isEmpty()) {
            Map<String, String> canonicalToExpected = new HashMap<>();
            for (String expectedProp : expectedProperties.keySet()) {
                canonicalToExpected.put(toCanonicalKey(expectedProp), expectedProp);
            }

            for (Map.Entry<String, Object> entry : new ArrayList<>(result.entrySet())) {
                String key = entry.getKey();
                Object val = entry.getValue();
                String canonical = toCanonicalKey(key);
                if (canonicalToExpected.containsKey(canonical)) {
                    String targetKey = canonicalToExpected.get(canonical);
                    if (!result.containsKey(targetKey) || result.get(targetKey) == null) {
                        result.put(targetKey, val);
                    }
                }
            }

            // Ensure array types
            for (Map.Entry<String, Object> propEntry : expectedProperties.entrySet()) {
                String propName = propEntry.getKey();
                if (propEntry.getValue() instanceof Map<?, ?> propDef) {
                    if ("array".equals(propDef.get("type"))) {
                        if (result.containsKey(propName) && !(result.get(propName) instanceof List<?>)) {
                            Object val = result.get(propName);
                            if (val instanceof String s) {
                                result.put(propName, List.of(s));
                            }
                        }
                    }
                }
            }

            if (!additionalPropertiesAllowed || "run_commands".equals(toolName)) {
                Set<String> validKeys = expectedProperties.keySet();
                result.keySet().removeIf(k -> !validKeys.contains(k));
            }
        }

        return result;
    }

    public String resolveAbsolutePath(String path, String workingDirectory) {
        if (path == null || path.isBlank()) return path;
        String cleanPath = path.trim().replace('\\', '/');

        if (cleanPath.matches("^[a-zA-Z]:/.*")) {
            return cleanPath;
        }

        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String cleanWorkDir = workingDirectory.trim().replace('\\', '/');
            if (cleanWorkDir.endsWith("/")) {
                cleanWorkDir = cleanWorkDir.substring(0, cleanWorkDir.length() - 1);
            }
            if (cleanPath.startsWith("/")) {
                cleanPath = cleanPath.substring(1);
            }
            String workDirFolderName = cleanWorkDir.substring(cleanWorkDir.lastIndexOf('/') + 1);
            if (cleanPath.startsWith(workDirFolderName + "/")) {
                cleanPath = cleanPath.substring(workDirFolderName.length() + 1);
            }
            return cleanWorkDir + "/" + cleanPath;
        }

        return cleanPath;
    }

    public String toRelativePath(String path, String workingDirectory) {
        if (path == null || path.isBlank()) return path;
        String cleanPath = path.trim().replace('\\', '/');

        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String cleanWorkDir = workingDirectory.trim().replace('\\', '/');
            if (cleanWorkDir.endsWith("/")) {
                cleanWorkDir = cleanWorkDir.substring(0, cleanWorkDir.length() - 1);
            }
            if (cleanPath.startsWith(cleanWorkDir + "/")) {
                return cleanPath.substring(cleanWorkDir.length() + 1);
            }
        }

        int srcIdx = cleanPath.indexOf("src/");
        if (srcIdx >= 0) {
            return cleanPath.substring(srcIdx);
        }

        if (cleanPath.startsWith("/")) {
            return cleanPath.substring(1);
        }

        return cleanPath;
    }

    public String extractWorkingDirectory(Map<String, Object> request) {
        if (request == null || !request.containsKey("messages")) {
            return null;
        }
        Object msgsObj = request.get("messages");
        if (!(msgsObj instanceof List<?> msgs)) {
            return null;
        }

        for (Object mObj : msgs) {
            if (!(mObj instanceof Map<?, ?> m)) continue;
            Object contentObj = m.get("content");
            if (contentObj instanceof String text) {
                Matcher matcher = ENV_WORKDIR_PATTERN.matcher(text);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        }

        for (Object mObj : msgs) {
            if (!(mObj instanceof Map<?, ?> m)) continue;
            Object contentObj = m.get("content");
            if (contentObj instanceof String text) {
                Matcher driveMatcher = DRIVE_PATH_PATTERN.matcher(text);
                if (driveMatcher.find()) {
                    String fullPath = driveMatcher.group(1).replace('\\', '/');
                    int srcIdx = fullPath.indexOf("/src/");
                    if (srcIdx > 0) {
                        return fullPath.substring(0, srcIdx);
                    }
                }
            }
        }

        return null;
    }

    public Map<String, Map<String, Object>> extractToolSchemas(Map<String, Object> request) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (request == null || !request.containsKey("tools")) {
            return result;
        }

        Object toolsObj = request.get("tools");
        if (!(toolsObj instanceof List<?> toolsList)) {
            return result;
        }

        for (Object tObj : toolsList) {
            if (!(tObj instanceof Map<?, ?> toolMap)) continue;
            Object fnObj = toolMap.get("function");
            if (!(fnObj instanceof Map<?, ?> fnMap)) continue;

            String name = (String) fnMap.get("name");
            if (name != null) {
                result.put(name, (Map<String, Object>) fnMap);
            }
        }

        return result;
    }

    private Map<String, Object> parseArguments(Object argsObj) {
        if (argsObj == null) {
            return new HashMap<>();
        }
        if (argsObj instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        if (argsObj instanceof String str) {
            if (str.isBlank()) return new HashMap<>();
            try {
                return mapper.readValue(str, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("Arguments string is not valid JSON: {}", str);
                return null;
            }
        }
        return null;
    }

    private String toCanonicalKey(String key) {
        if (key == null) return "";
        return key.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void copyIfMissing(Map<String, Object> map, String targetKey, String... candidateKeys) {
        if (!map.containsKey(targetKey) || map.get(targetKey) == null) {
            for (String candidate : candidateKeys) {
                if (map.containsKey(candidate) && map.get(candidate) != null) {
                    map.put(targetKey, map.get(candidate));
                    break;
                }
            }
        }
    }

    private void ensureStringArray(Map<String, Object> result, String arrayProp, String... singleProps) {
        if (!result.containsKey(arrayProp) || result.get(arrayProp) == null) {
            for (String single : singleProps) {
                if (result.containsKey(single) && result.get(single) != null) {
                    Object val = result.remove(single);
                    if (val instanceof List<?>) {
                        result.put(arrayProp, val);
                    } else if (val instanceof String s) {
                        result.put(arrayProp, List.of(s));
                    }
                    break;
                }
            }
        } else if (result.get(arrayProp) instanceof String s) {
            result.put(arrayProp, List.of(s));
        }
    }
}
