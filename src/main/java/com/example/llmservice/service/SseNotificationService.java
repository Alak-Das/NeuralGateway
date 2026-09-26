package com.example.llmservice.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service managing SSE emitters for real-time status updates.
 * Handles emitter lifecycle, broadcasting, and cleanup of dead emitters.
 */
@Service
public class SseNotificationService {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Subscribe to real-time status updates.
     * @return SseEmitter that will receive status events
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((ex) -> emitters.remove(emitter));

        return emitter;
    }

    /**
     * Broadcast a status update to all connected emitters.
     * Dead emitters are automatically removed.
     */
    public void broadcast(Object data) {
        List<SseEmitter> deadEmitters = new ArrayList<>();
        
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(data));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        // Remove dead emitters
        deadEmitters.forEach(emitters::remove);
    }

    /**
     * Broadcast an initial state to a specific emitter (used on connect).
     */
    public void sendInitialState(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(data));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
    }

    /**
     * Get the number of currently connected emitters.
     */
    public int getConnectedCount() {
        return emitters.size();
    }

    /**
     * Close all emitters (e.g., on shutdown).
     */
    public void closeAll() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }
}