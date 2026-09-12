package ar.unrn.keycloak.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes Keycloak events to RabbitMQ using a topic exchange.
 *
 * <p>Routing key conventions:
 * <ul>
 *   <li>User events:  {@code keycloak.user.<EVENT_TYPE>}
 *       (e.g. {@code keycloak.user.LOGIN})</li>
 *   <li>Admin events: {@code keycloak.admin.<RESOURCE_TYPE>.<OPERATION_TYPE>}
 *       (e.g. {@code keycloak.admin.USER.CREATE})</li>
 * </ul>
 *
 * <p>The primary use case is user lifecycle: CREATE, UPDATE, DELETE fired as
 * {@link AdminEvent}s when an admin or the REST API mutates a user.
 * Spring Boot consumers can subscribe with {@code keycloak.admin.USER.#}.
 */
public class RabbitMQEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProvider.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AMQP.BasicProperties MESSAGE_PROPERTIES_JSON = new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .contentEncoding(StandardCharsets.UTF_8.name())
            .deliveryMode(2)
            .build();

    private final Supplier<Channel> channelSupplier;
    private final String exchange;

    public RabbitMQEventListenerProvider(Channel channel, String exchange) {
        this(() -> channel, exchange);
    }

    public RabbitMQEventListenerProvider(Supplier<Channel> channelSupplier, String exchange) {
        this.channelSupplier = channelSupplier;
        this.exchange = exchange;
    }

    /**
     * Handles regular user-facing events (login, logout, register, etc.).
     *
     * <p>Routing key: {@code keycloak.user.<EVENT_TYPE>}
     */
    @Override
    public void onEvent(Event event) {
        if (event.getType() == null) {
            return;
        }

        String routingKey = "keycloak.user." + event.getType().name();

        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("type", event.getType().name());
            payload.put("realmId", event.getRealmId());
            payload.put("clientId", event.getClientId());
            payload.put("userId", event.getUserId());
            payload.put("ipAddress", event.getIpAddress());
            payload.put("time", event.getTime());

            if (event.getError() != null) {
                payload.put("error", event.getError());
            }
            if (event.getDetails() != null) {
                ObjectNode details = MAPPER.createObjectNode();
                event.getDetails().forEach(details::put);
                payload.set("details", details);
            }

            publish(routingKey, payload);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("Failed to publish user event [%s] to RabbitMQ", routingKey), e);
        }
    }

    /**
     * Handles admin events: user CRUD, role assignments, client changes, etc.
     *
     * <p>Routing key: {@code keycloak.admin.<RESOURCE_TYPE>.<OPERATION_TYPE>}
     * <br>Examples:
     * <ul>
     *   <li>{@code keycloak.admin.USER.CREATE}</li>
     *   <li>{@code keycloak.admin.USER.UPDATE}</li>
     *   <li>{@code keycloak.admin.USER.DELETE}</li>
     * </ul>
     *
     * <p>When {@code includeRepresentation} is true the full user JSON is
     * available in {@code event.getRepresentation()}.
     */
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == null || event.getOperationType() == null) {
            return;
        }

        String routingKey = "keycloak.admin."
                + event.getResourceType().name() + "."
                + event.getOperationType().name();

        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("resourceType", event.getResourceType().name());
            payload.put("operationType", event.getOperationType().name());
            payload.put("realmId", event.getRealmId());
            payload.put("resourcePath", event.getResourcePath());
            payload.put("time", event.getTime());

            if (event.getAuthDetails() != null) {
                ObjectNode auth = MAPPER.createObjectNode();
                auth.put("realmId", event.getAuthDetails().getRealmId());
                auth.put("clientId", event.getAuthDetails().getClientId());
                auth.put("userId", event.getAuthDetails().getUserId());
                auth.put("ipAddress", event.getAuthDetails().getIpAddress());
                payload.set("auth", auth);
            }

            // representation contains the full entity JSON (user, role, etc.)
            if (includeRepresentation && event.getRepresentation() != null) {
                try {
                    payload.set("representation",
                            MAPPER.readTree(event.getRepresentation()));
                } catch (Exception parseEx) {
                    payload.put("representation", event.getRepresentation());
                }
            }

            publish(routingKey, payload);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("Failed to publish admin event [%s] to RabbitMQ", routingKey), e);
        }
    }

    @Override
    public void close() {
        // channel lifecycle is managed by the Factory — nothing to do here
    }

    private void publish(String routingKey, ObjectNode payload) throws Exception {
        Channel ch = channelSupplier != null ? channelSupplier.get() : null;
        if (ch == null || !ch.isOpen()) {
            LOG.warning(String.format("RabbitMQ channel not available — dropping event [%s]", routingKey));
            return;
        }

        byte[] body = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        synchronized (ch) {
            ch.basicPublish(exchange, routingKey, MESSAGE_PROPERTIES_JSON, body);
        }

        LOG.fine(String.format("Published event to exchange '%s' with routing key '%s'", exchange, routingKey));
    }
}
