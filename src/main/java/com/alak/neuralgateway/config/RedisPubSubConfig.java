package com.alak.neuralgateway.config;

import com.alak.neuralgateway.service.SseNotificationService;
import com.alak.neuralgateway.service.ModelStatusService;
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
    public MessageListenerAdapter listenerAdapter(SseNotificationService sseNotificationService,
                                                  ModelStatusService modelStatusService,
                                                  com.alak.neuralgateway.service.RedisPersistenceService redisPersistenceService,
                                                  ObjectMapper objectMapper) {
        return new MessageListenerAdapter(
                new SseMessageSubscriber(sseNotificationService, modelStatusService, redisPersistenceService, objectMapper), "onMessage");
    }

    public static class SseMessageSubscriber {
        private final SseNotificationService sseNotificationService;
        private final ModelStatusService modelStatusService;
        private final com.alak.neuralgateway.service.RedisPersistenceService redisPersistenceService;
        private final ObjectMapper objectMapper;

        public SseMessageSubscriber(SseNotificationService sseNotificationService,
                                    ModelStatusService modelStatusService,
                                    com.alak.neuralgateway.service.RedisPersistenceService redisPersistenceService,
                                    ObjectMapper objectMapper) {
            this.sseNotificationService = sseNotificationService;
            this.modelStatusService = modelStatusService;
            this.redisPersistenceService = redisPersistenceService;
            this.objectMapper = objectMapper;
        }

        public void onMessage(String message, String channel) {
            try {
                com.alak.neuralgateway.domain.ModelStatus status =
                        objectMapper.readValue(message, com.alak.neuralgateway.domain.ModelStatus.class);
                modelStatusService.updateFromRemote(status);
                sseNotificationService.broadcast(java.util.Map.of(
                        "models", modelStatusService.getAllStatuses(),
                        "requesters", redisPersistenceService.getRequesterTelemetryDetailed()
                ));
            } catch (Exception e) {
                // Ignore malformed or legacy notification payloads rather than disrupting the listener.
            }
        }
    }
}
