package com.alak.neuralgateway.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Value object representing an LLM model with its metadata and capabilities.
 */
public class Model {
    private final String id;
    private final String name;
    private final String providerId;
    private final Set<Pipeline> pipelines;
    private final int contextLimit;
    private final int priority;
    private final Map<Pipeline, Integer> pipelinePriorities;
    private final ModelCapabilities capabilities;
    private final boolean enabled;

    public Model(String id, String name, String providerId, Set<Pipeline> pipelines, int contextLimit, int priority, ModelCapabilities capabilities) {
        this(id, name, providerId, pipelines, contextLimit, priority, Map.of(), capabilities, true);
    }

    public Model(String id, String name, String providerId, Set<Pipeline> pipelines, int contextLimit,
                 int priority, Map<Pipeline, Integer> pipelinePriorities, ModelCapabilities capabilities) {
        this(id, name, providerId, pipelines, contextLimit, priority, pipelinePriorities, capabilities, true);
    }

    public Model(String id, String name, String providerId, Set<Pipeline> pipelines, int contextLimit,
                 int priority, Map<Pipeline, Integer> pipelinePriorities, ModelCapabilities capabilities,
                 boolean enabled) {
        this.id = id;
        this.name = name;
        this.providerId = providerId;
        this.pipelines = pipelines != null ? Collections.unmodifiableSet(pipelines) : Collections.emptySet();
        this.contextLimit = contextLimit;
        this.priority = priority;
        Map<Pipeline, Integer> priorities = new EnumMap<>(Pipeline.class);
        if (pipelinePriorities != null) priorities.putAll(pipelinePriorities);
        this.pipelinePriorities = Collections.unmodifiableMap(priorities);
        this.capabilities = capabilities != null ? capabilities : ModelCapabilities.NONE;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getProviderId() {
        return providerId;
    }

    public Set<Pipeline> getPipelines() {
        return pipelines;
    }

    public int getContextLimit() {
        return contextLimit;
    }

    public int getPriority() {
        return priority;
    }

    public int getPriority(Pipeline pipeline) {
        return pipelinePriorities.getOrDefault(pipeline, priority);
    }

    public Map<Pipeline, Integer> getPipelinePriorities() { return pipelinePriorities; }

    public ModelCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Whether this model participates in routing and periodic health checks.
     * Disabled models stay registered (visible in dashboard/status endpoints)
     * but are excluded from request routing and health-check sweeps.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAvailableForPipeline(Pipeline pipeline) {
        return pipelines.contains(pipeline);
    }

    public boolean canHandleContext(int estimatedTokens) {
        return estimatedTokens <= contextLimit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Model)) return false;
        Model model = (Model) o;
        return id.equals(model.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Model{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", pipelines=" + pipelines +
                ", contextLimit=" + contextLimit +
                ", capabilities=" + capabilities +
                ", enabled=" + enabled +
                '}';
    }

    /**
     * Enum representing the different model pipelines.
     */
    public enum Pipeline {
        CODING,
        REASONING,
        VISION
    }

    /**
     * Value object representing model capabilities.
     */
    public static class ModelCapabilities {
        public static final ModelCapabilities NONE = new ModelCapabilities(false, false, false);
        
        private final boolean supportsVision;
        private final boolean supportsTools;
        private final boolean supportsJsonMode;

        public ModelCapabilities(boolean supportsVision, boolean supportsTools, boolean supportsJsonMode) {
            this.supportsVision = supportsVision;
            this.supportsTools = supportsTools;
            this.supportsJsonMode = supportsJsonMode;
        }

        public boolean isSupportsVision() {
            return supportsVision;
        }

        public boolean isSupportsTools() {
            return supportsTools;
        }

        public boolean isSupportsJsonMode() {
            return supportsJsonMode;
        }

        @Override
        public String toString() {
            return "ModelCapabilities{" +
                    "supportsVision=" + supportsVision +
                    ", supportsTools=" + supportsTools +
                    ", supportsJsonMode=" + supportsJsonMode +
                    '}';
        }
    }
}
