package com.example.llmservice.event;

import com.example.llmservice.config.RedisPubSubConfig;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Listens for model status changes and publishes a notification to Redis Pub/Sub,
 * so all gateway instances can broadcast to their connected UI clients.
 */
@Component
public class ModelStatusEventListener {

    private final StringRedisTemplate redisTemplate;

    public ModelStatusEventListener(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @EventListener
    public void handleModelStatusChange(ModelStatusChangedEvent event) {
        // Publish a lightweight signal to the Redis channel
        redisTemplate.convertAndSend(RedisPubSubConfig.SSE_STATUS_CHANNEL, "UPDATE:" + event.getModelStatus().model());
    }
}
