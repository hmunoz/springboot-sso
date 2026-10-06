package ar.unrn.video.catalog.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Catalog only publishes; it never binds a queue to this exchange. Declaring
 * the exchange here too (idempotent, same durable/topic shape as
 * membership-service) means the topology does not depend on which of the two
 * services happens to start first.
 */
@Configuration
public class RabbitMQConfig {

    @Value("${videoclub.rabbitmq.exchange:videoclub.events}")
    private String videoclubExchangeName;

    @Bean
    public TopicExchange videoclubEventsExchange() {
        return new TopicExchange(videoclubExchangeName, true, false);
    }

    /**
     * Spring Boot's auto-configured RabbitTemplate falls back to
     * {@code SimpleMessageConverter} otherwise, which only accepts String,
     * byte[] or Serializable payloads and rejects a plain record like
     * {@code MoviePayload}.
     */
    @Bean
    public MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

}
