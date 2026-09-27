package com.alak.neuralgateway;

import com.alak.neuralgateway.service.SseNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class SseNotificationServiceTest {

    @Test
    void testSubscribeAndBroadcast() {
        SseNotificationService service = new SseNotificationService();
        assertEquals(0, service.getConnectedCount());

        SseEmitter emitter = service.subscribe();
        assertEquals(1, service.getConnectedCount());

        assertDoesNotThrow(() -> service.broadcast("test-data"));
    }

    @Test
    void testDeadEmittersArePrunedOnException() {
        SseNotificationService service = new SseNotificationService();
        SseEmitter emitter = service.subscribe();
        assertEquals(1, service.getConnectedCount());

        // Completing the emitter simulates client disconnect
        emitter.complete();

        // Broadcast should not throw IllegalStateException even though emitter is completed
        assertDoesNotThrow(() -> service.broadcast("test-payload"));

        // Dead emitter should have been removed
        assertEquals(0, service.getConnectedCount());
    }

    @Test
    void testSendInitialStateSafelyHandlesClosedEmitter() {
        SseNotificationService service = new SseNotificationService();
        SseEmitter emitter = service.subscribe();
        emitter.complete();

        assertDoesNotThrow(() -> service.sendInitialState(emitter, "init-data"));
        assertEquals(0, service.getConnectedCount());
    }
}
