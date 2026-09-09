package ar.unrn.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class SseEmitterManager {

    // 30 minutes timeout
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE connection for a user.
     */
    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.info("SSE client connected for user [{}]. Total connections for user: {}", userId, emittersByUser.get(userId).size());

        Runnable cleanup = () -> removeEmitter(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE connection error for user [{}]: {}", userId, e.getMessage());
            cleanup.run();
        });

        // Send initial handshake event
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of(
                            "status", "CONNECTED",
                            "userId", userId,
                            "timestamp", System.currentTimeMillis()
                    )));
        } catch (IOException e) {
            log.warn("Failed to send initial SSE handshake to user [{}]: {}", userId, e.getMessage());
            cleanup.run();
        }

        return emitter;
    }

    /**
     * Sends an event to all active SSE connections of a specific user.
     */
    public void sendToUser(String userId, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE connections for user [{}] to deliver event [{}]", userId, eventName);
            return;
        }

        log.info("Dispatching SSE event [{}] to user [{}] across {} connection(s)", eventName, userId, emitters.size());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                log.debug("Failed to deliver SSE event to user [{}], removing emitter: {}", userId, e.getMessage());
                removeEmitter(userId, emitter);
            }
        }
    }

    /**
     * Broadcasts an event to all currently connected users.
     */
    public void broadcast(String eventName, Object data) {
        emittersByUser.keySet().forEach(userId -> sendToUser(userId, eventName, data));
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByUser.remove(userId);
                log.info("All SSE connections closed for user [{}]. Removed from registry.", userId);
            } else {
                log.info("Closed one SSE connection for user [{}]. Remaining: {}", userId, list.size());
            }
        }
    }

    public int getActiveUsersCount() {
        return emittersByUser.size();
    }
}
