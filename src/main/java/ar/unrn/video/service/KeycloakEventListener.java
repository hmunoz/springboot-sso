package ar.unrn.video.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakEventListener {

    private final SseEmitterManager sseEmitterManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = "${keycloak.rabbitmq.queue:keycloak-events}")
    public void onKeycloakEvent(String messagePayload,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.info("Received Keycloak event from RabbitMQ [{}]: {}", routingKey, messagePayload);

        Map<String, Object> event;
        try {
            event = objectMapper.readValue(messagePayload, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse Keycloak event payload as JSON: {}", e.getMessage());
            return;
        }

        // 1. User events (e.g. keycloak.user.LOGIN, REGISTER, VERIFY_EMAIL, UPDATE_TOTP)
        if (event.containsKey("type")) {
            String type = String.valueOf(event.get("type"));
            String userId = (String) event.get("userId");

            log.info("Forwarding user event [{}] (userId: {}) to SSE clients", type, userId);
            // Send targeted event if user is connected
            if (userId != null && !userId.isBlank()) {
                sseEmitterManager.sendToUser(userId, "AUTH_EVENT", event);
            }
            // Also broadcast to all active sessions (e.g. admins monitoring the platform)
            sseEmitterManager.broadcast("AUTH_EVENT", event);
            return;
        }

        // 2. Admin events (e.g. keycloak.admin.USER.CREATE, UPDATE, DELETE)
        if (event.containsKey("operationType")) {
            String operationType = String.valueOf(event.get("operationType"));
            String resourceType = String.valueOf(event.get("resourceType"));
            String resourcePath = String.valueOf(event.get("resourcePath"));

            log.info("Forwarding admin event [{}:{}] to SSE clients", operationType, resourceType);
            if ("USER".equalsIgnoreCase(resourceType) && resourcePath != null && resourcePath.startsWith("users/")) {
                String userId = resourcePath.substring("users/".length());
                sseEmitterManager.sendToUser(userId, "ADMIN_EVENT", event);
            }
            // Also broadcast admin events to all active sessions
            sseEmitterManager.broadcast("ADMIN_EVENT", event);
        }
    }
}
