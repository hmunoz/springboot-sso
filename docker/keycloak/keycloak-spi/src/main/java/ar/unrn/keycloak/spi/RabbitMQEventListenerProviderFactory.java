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
    private static final long MIN_RECONNECT_INTERVAL_MS = 5000;

    private Connection connection;
    private Channel channel;
    private String exchange;

    private String host;
    private int port;
    private String user;
    private String pass;
    private String vhost;
    private long lastConnectAttemptTime = 0;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(org.keycloak.Config.Scope config) {
        host = env("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(env("RABBITMQ_PORT", "5672"));
        user = env("RABBITMQ_USER", "guest");
        pass = env("RABBITMQ_PASS", "guest");
        vhost = env("RABBITMQ_VHOST", "/");
        exchange = env("RABBITMQ_EXCHANGE", "keycloak.events");

        tryConnect();
    }

    private synchronized void tryConnect() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(user);
            factory.setPassword(pass);
            factory.setVirtualHost(vhost);
            factory.setAutomaticRecoveryEnabled(true);
            factory.setConnectionTimeout(3000);

            connection = factory.newConnection("keycloak-spi");
            channel = connection.createChannel();

            // The producer declares what it needs to publish to.
            channel.exchangeDeclare(exchange, "topic", true);

            LOG.info(String.format("RabbitMQ SPI connected to %s:%d (exchange: %s, declared as durable topic)",
                    host, port, exchange));
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("Failed to connect to RabbitMQ at %s:%d — will retry on next event: %s",
                    host, port, e.getMessage()));
            tryClose();
        }
    }

    private synchronized void tryClose() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
        } finally {
            channel = null;
        }

        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {
        } finally {
            connection = null;
        }
    }

    public synchronized Channel getChannel() {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        long now = System.currentTimeMillis();
        if (now - lastConnectAttemptTime < MIN_RECONNECT_INTERVAL_MS) {
            return null;
        }
        lastConnectAttemptTime = now;
        tryClose();
        tryConnect();
        return channel;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMQEventListenerProvider(this::getChannel, exchange);
    }

    @Override
    public void close() {
        tryClose();
        LOG.info("RabbitMQ SPI connection closed");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
