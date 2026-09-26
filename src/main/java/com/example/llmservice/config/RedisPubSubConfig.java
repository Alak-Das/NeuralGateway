package com.example.llmservice.config;

import com.example.llmservice.service.SseNotificationService;
import com.example.llmservice.service.ModelStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisPubSubConfig {

    public static final String SSE_STATUS_CHANNEL = "sse-status-updates";

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                                   MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(SSE_STATUS_CHANNEL));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(SseNotificationService sseNotificationService, ModelStatusService modelStatusService) {
        return new MessageListenerAdapter(new SseMessageSubscriber(sseNotificationService, modelStatusService), "onMessage");
    }

    public static class SseMessageSubscriber {
        private final SseNotificationService sseNotificationService;
        private final ModelStatusService modelStatusService;

        public SseMessageSubscriber(SseNotificationService sseNotificationService, ModelStatusService modelStatusService) {
            this.sseNotificationService = sseNotificationService;
            this.modelStatusService = modelStatusService;
        }

        public void onMessage(String message, String channel) {
            // When a message is received from Redis, broadcast local state to connected clients.
            // (We could parse the message payload if we wanted incremental updates,
            // but broadcasting the whole status list keeps it simple and syncs the state).
            sseNotificationService.broadcast(modelStatusService.getAllStatuses());
        }
    }
}
