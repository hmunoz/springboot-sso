package ar.unrn.video.service;

import ar.unrn.video.model.KeycloakEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakEventListener {

    private final SseEmitterManager sseEmitterManager;

    @RabbitListener(queues = "${keycloak.rabbitmq.queue:keycloak-events}")
    public void onKeycloakEvent(KeycloakEvent event,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.info("Received Keycloak event from RabbitMQ [{}]: {}", routingKey, event);

        // 1. User events (e.g. keycloak.user.LOGIN, REGISTER, VERIFY_EMAIL, UPDATE_TOTP)
        if (event.isUserEvent()) {
            log.info("Forwarding user event [{}] (userId: {}) to SSE clients", event.type(), event.userId());
            sseEmitterManager.broadcast("AUTH_EVENT", event);
            return;
        }

        // 2. Admin events (e.g. keycloak.admin.USER.CREATE, UPDATE, DELETE)
        if (event.isAdminEvent()) {
            log.info("Forwarding admin event [{}:{}] (targetUserId: {}) to SSE clients",
                    event.operationType(), event.resourceType(), event.extractTargetUserId());
            sseEmitterManager.broadcast("ADMIN_EVENT", event);
        }
    }
}
