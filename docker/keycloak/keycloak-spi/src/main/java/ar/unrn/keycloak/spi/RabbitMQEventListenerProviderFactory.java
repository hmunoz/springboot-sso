package ar.unrn.keycloak.spi;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for the RabbitMQ event listener SPI.
 *
 * <p>Reads connection settings from environment variables and opens a single
 * shared AMQP connection/channel for the lifetime of the Keycloak container.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>RABBITMQ_HOST (default: localhost)</li>
 *   <li>RABBITMQ_PORT (default: 5672)</li>
 *   <li>RABBITMQ_USER (default: guest)</li>
 *   <li>RABBITMQ_PASS (default: guest)</li>
 *   <li>RABBITMQ_VHOST (default: /)</li>
 *   <li>RABBITMQ_EXCHANGE (default: keycloak.events) — declared as a durable topic
 *       exchange on startup, so it does not need to pre-exist</li>
 * </ul>
 */
public class RabbitMQEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProviderFactory.class.getName());
    public static final String PROVIDER_ID = "rabbitmq-event-listener";

    private Connection connection;
    private Channel channel;
    private String exchange;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(org.keycloak.Config.Scope config) {
        String host = env("RABBITMQ_HOST", "localhost");
        int port = Integer.parseInt(env("RABBITMQ_PORT", "5672"));
        String user = env("RABBITMQ_USER", "guest");
        String pass = env("RABBITMQ_PASS", "guest");
        String vhost = env("RABBITMQ_VHOST", "/");
        exchange = env("RABBITMQ_EXCHANGE", "keycloak.events");

        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(user);
            factory.setPassword(pass);
            factory.setVirtualHost(vhost);
            factory.setAutomaticRecoveryEnabled(true);

            connection = factory.newConnection("keycloak-spi");
            channel = connection.createChannel();

            // The producer declares what it needs to publish to. Without this, pointing
            // RABBITMQ_EXCHANGE at anything other than the built-in amq.topic makes the
            // first publish fail with a 404 and close this long-lived channel, after which
            // every event is silently dropped until Keycloak is restarted.
            // Declaring is idempotent, so re-declaring amq.topic with matching properties
            // is a no-op.
            channel.exchangeDeclare(exchange, "topic", true);

            LOG.info(String.format("RabbitMQ SPI connected to %s:%d (exchange: %s, declared as durable topic)",
                    host, port, exchange));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("Failed to connect to RabbitMQ at %s:%d — events will not be published", host, port), e);
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMQEventListenerProvider(channel, exchange);
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            LOG.info("RabbitMQ SPI connection closed");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error closing RabbitMQ connection", e);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
