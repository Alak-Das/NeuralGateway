package com.alak.neuralgateway.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Lets MVC write a StreamingResponseBody when it is wrapped in ResponseEntity.
 *
 * The OpenAI endpoint also returns ordinary JSON for non-streaming requests, so
 * its declared return type cannot be StreamingResponseBody. Without this
 * converter MVC tries to serialize the streaming lambda as a regular response
 * body and rejects it for text/event-stream.
 */
@Configuration
public class StreamingResponseBodyMessageConverter implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new StreamingBodyConverter());
    }

    /**
     * Increase the async request timeout to match the upstream WebClient timeout (120s)
     * plus a generous buffer. Without this, Spring MVC defaults to 30 seconds, which
     * causes AsyncRequestTimeoutException for slow LLM providers (cold starts, large models).
     * The interrupted thread cascades into RedisCommandInterruptedException in Redis operations.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(180_000); // 3 minutes — exceeds WebClient's 120s responseTimeout
    }

    private static final class StreamingBodyConverter extends AbstractHttpMessageConverter<StreamingResponseBody> {

        private StreamingBodyConverter() {
            super(new MediaType("text", "event-stream", StandardCharsets.UTF_8));
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return StreamingResponseBody.class.isAssignableFrom(clazz);
        }

        @Override
        protected StreamingResponseBody readInternal(Class<? extends StreamingResponseBody> clazz,
                                                     HttpInputMessage inputMessage) {
            throw new UnsupportedOperationException("Streaming response bodies are write-only");
        }

        @Override
        protected void writeInternal(StreamingResponseBody body, HttpOutputMessage outputMessage)
                throws IOException {
            body.writeTo(outputMessage.getBody());
        }
    }
}
