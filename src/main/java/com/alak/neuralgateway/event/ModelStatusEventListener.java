package com.alak.neuralgateway.event;

import com.alak.neuralgateway.config.RedisPubSubConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for model status changes and publishes a notification to Redis Pub/Sub,
 * so all gateway instances can broadcast to their connected UI clients.
 */
@Component
public class ModelStatusEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModelStatusEventListener.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ModelStatusEventListener(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void handleModelStatusChange(ModelStatusChangedEvent event) {
        try {
            String statusJson = objectMapper.writeValueAsString(event.getModelStatus());
            redisTemplate.convertAndSend(RedisPubSubConfig.SSE_STATUS_CHANNEL, statusJson);
        } catch (JsonProcessingException e) {
            log.warn("Unable to publish model status update for '{}': {}",
                    event.getModelStatus().model(), e.getMessage());
        }
    }
}
