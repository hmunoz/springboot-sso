package ar.unrn.video.service;

import ar.unrn.video.event.Event;
import ar.unrn.video.event.MessagePublisher;
import ar.unrn.video.event.SocioPayload;
import ar.unrn.video.model.KeycloakEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Anti-Corruption Layer between Keycloak and the videoclub domain.
 *
 * <p>Two responsibilities, deliberately kept together because both are driven by the same
 * inbound message:
 * <ol>
 *   <li>fan out the raw event over SSE for the real-time notification toasts;</li>
 *   <li>translate the provider-specific event into a canonical {@link Event} and publish
 *       it to the business exchange.</li>
 * </ol>
 *
 * <p>The SSE dispatch runs first because it is best-effort and cannot fail meaningfully,
 * while the domain publish can throw. Letting that exception propagate is what keeps the
 * inbound message unacknowledged so RabbitMQ redelivers it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakEventListener {

    private static final String AGGREGATE = "Socio";

    private static final String RESOURCE_TYPE_USER = "USER";
    private static final String USER_EVENT_REGISTER = "REGISTER";

    private final SseEmitterManager sseEmitterManager;
    private final MessagePublisher messagePublisher;

    @RabbitListener(queues = "${keycloak.rabbitmq.queue:keycloak-events}")
    public void onKeycloakEvent(KeycloakEvent event,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.info("Received Keycloak event from RabbitMQ [{}]: {}", routingKey, event);

        // 1. User events (e.g. keycloak.user.LOGIN, REGISTER, VERIFY_EMAIL, UPDATE_TOTP)
        if (event.isUserEvent()) {
            log.info("Forwarding user event [{}] (userId: {}) to SSE clients", event.type(), event.userId());
            sseEmitterManager.broadcast("AUTH_EVENT", event);
            traducirEventoDeUsuario(event);
            return;
        }

        // 2. Admin events (e.g. keycloak.admin.USER.CREATE, UPDATE, DELETE)
        if (event.isAdminEvent()) {
            log.info("Forwarding admin event [{}:{}] (targetUserId: {}) to SSE clients",
                    event.operationType(), event.resourceType(), event.extractTargetUserId());
            sseEmitterManager.broadcast("ADMIN_EVENT", event);
            traducirEventoDeAdmin(event);
        }
    }

    /**
     * Self-registration is the only user event that creates a member. Every other user
     * event (logins, token refreshes, OTP setup) carries no membership change.
     */
    private void traducirEventoDeUsuario(final KeycloakEvent event) {
        if (!USER_EVENT_REGISTER.equals(event.type())) {
            return;
        }

        final String userId = event.extractTargetUserId();
        if (userId == null) {
            log.warn("REGISTER event without a resolvable userId, cannot publish domain event");
            return;
        }

        // User events use snake_case keys in the details map.
        final Map<String, String> details = event.details() != null ? event.details() : Map.of();
        final SocioPayload payload = new SocioPayload(
                userId,
                details.get("email"),
                details.get("username"),
                details.get("first_name"),
                details.get("last_name")
        );

        publicar(Event.Type.CREATE, payload);
    }

    private void traducirEventoDeAdmin(final KeycloakEvent event) {
        if (!RESOURCE_TYPE_USER.equals(event.resourceType())) {
            return;
        }

        final String userId = event.extractTargetUserId();
        if (userId == null) {
            log.warn("Admin event [{}] without a resolvable target userId, cannot publish domain event",
                    event.operationType());
            return;
        }

        switch (event.operationType()) {
            case "CREATE" -> publicar(Event.Type.CREATE, payloadDesdeRepresentation(event, userId));
            case "UPDATE" -> publicar(Event.Type.UPDATE, payloadDesdeRepresentation(event, userId));
            case "DELETE" -> publicar(Event.Type.DELETE, SocioPayload.ofKey(userId));
            default -> log.debug("Admin operation [{}] carries no membership change, ignored",
                    event.operationType());
        }
    }

    /**
     * Admin events carry the full entity under {@code representation}, published by the SPI
     * as a JSON object and therefore deserialized as a map. Keys are camelCase here, unlike
     * the snake_case used by user event details.
     */
    private SocioPayload payloadDesdeRepresentation(final KeycloakEvent event, final String userId) {
        final Map<?, ?> rep = event.representation() instanceof Map<?, ?> map ? map : Map.of();

        return new SocioPayload(
                userId,
                texto(rep.get("email")),
                texto(rep.get("username")),
                texto(rep.get("firstName")),
                texto(rep.get("lastName"))
        );
    }

    private void publicar(final Event.Type type, final SocioPayload payload) {
        messagePublisher.publish(Event.of(AGGREGATE, type, payload.keycloakId(), payload));
    }

    private static String texto(final Object value) {
        return value != null ? String.valueOf(value) : null;
    }

}
