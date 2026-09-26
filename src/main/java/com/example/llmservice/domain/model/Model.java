package com.example.llmservice.domain.model;

import java.util.Collections;
import java.util.Set;

/**
 * Value object representing an LLM model with its metadata and capabilities.
 */
public class Model {
    private final String id;
    private final String name;
    private final Pipeline pipeline;
    private final int contextLimit;
    private final ModelCapabilities capabilities;

    public Model(String id, String name, Pipeline pipeline, int contextLimit, ModelCapabilities capabilities) {
        this.id = id;
        this.name = name;
        this.pipeline = pipeline;
        this.contextLimit = contextLimit;
        this.capabilities = capabilities != null ? capabilities : ModelCapabilities.NONE;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public int getContextLimit() {
        return contextLimit;
    }

    public ModelCapabilities getCapabilities() {
        return capabilities;
    }

    public boolean isAvailableForPipeline(Pipeline pipeline) {
        return this.pipeline == pipeline;
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
                ", pipeline=" + pipeline +
                ", contextLimit=" + contextLimit +
                ", capabilities=" + capabilities +
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