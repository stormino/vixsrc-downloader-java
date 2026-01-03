package com.github.stormino.service;

import com.github.stormino.model.ProgressUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Service
public class ProgressBroadcastService {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // Vaadin UI listeners for push updates
    private final CopyOnWriteArrayList<Consumer<ProgressUpdate>> uiListeners = new CopyOnWriteArrayList<>();

    /**
     * Register a new SSE emitter
     */
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String id = "global";

        emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(id, emitter));
        emitter.onTimeout(() -> removeEmitter(id, emitter));
        emitter.onError(e -> removeEmitter(id, emitter));

        log.info("New SSE emitter registered. Total: {}", emitters.get(id).size());

        return emitter;
    }

    /**
     * Register a UI listener for push updates
     */
    public void registerListener(Consumer<ProgressUpdate> listener) {
        uiListeners.add(listener);
        log.info("UI listener registered. Total: {}", uiListeners.size());
    }

    /**
     * Unregister a UI listener
     */
    public void unregisterListener(Consumer<ProgressUpdate> listener) {
        uiListeners.remove(listener);
        log.info("UI listener unregistered. Remaining: {}", uiListeners.size());
    }

    /**
     * Broadcast progress update to all connected clients
     */
    public void broadcastProgress(ProgressUpdate update) {
        log.debug("Broadcasting progress for task {}: {}%", update.getTaskId(), update.getProgress());

        // Broadcast to SSE clients
        broadcastToSSE(update);

        // Broadcast to Vaadin UI listeners
        broadcastToUI(update);
    }

    private void broadcastToSSE(ProgressUpdate update) {
        String id = "global";
        CopyOnWriteArrayList<SseEmitter> emitterList = emitters.get(id);

        if (emitterList == null || emitterList.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitterList) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(update));

            } catch (IOException e) {
                log.warn("Failed to send SSE event: {}", e.getMessage());
                removeEmitter(id, emitter);
            }
        }
    }

    private void broadcastToUI(ProgressUpdate update) {
        for (Consumer<ProgressUpdate> listener : uiListeners) {
            try {
                listener.accept(update);
            } catch (Exception e) {
                log.error("Error in UI listener: {}", e.getMessage(), e);
            }
        }
    }

    private void removeEmitter(String id, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitterList = emitters.get(id);
        if (emitterList != null) {
            emitterList.remove(emitter);
            log.info("SSE emitter removed. Remaining: {}", emitterList.size());
        }
    }

    public int getActiveConnections() {
        return emitters.values().stream()
                .mapToInt(CopyOnWriteArrayList::size)
                .sum();
    }
}
