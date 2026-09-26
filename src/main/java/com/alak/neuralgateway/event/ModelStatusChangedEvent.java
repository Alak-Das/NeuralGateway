package com.alak.neuralgateway.event;

import com.alak.neuralgateway.domain.ModelStatus;
import org.springframework.context.ApplicationEvent;

/**
 * Event published whenever a model's status is updated.
 */
public class ModelStatusChangedEvent extends ApplicationEvent {
    
    private final ModelStatus modelStatus;

    public ModelStatusChangedEvent(Object source, ModelStatus modelStatus) {
        super(source);
        this.modelStatus = modelStatus;
    }

    public ModelStatus getModelStatus() {
        return modelStatus;
    }
}
