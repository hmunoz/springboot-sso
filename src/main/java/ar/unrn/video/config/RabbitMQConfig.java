package ar.unrn.video.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RabbitMQConfig {

    // --- Infrastructure topology: events published by the Keycloak SPI ---

    @Value("${keycloak.rabbitmq.exchange:amq.topic}")
    private String exchangeName;

    @Value("${keycloak.rabbitmq.queue:keycloak-events}")
    private String queueName;

    // --- Business topology: canonical domain events ---

    @Value("${videoclub.rabbitmq.exchange:videoclub.events}")
    private String videoclubExchangeName;

    @Value("${videoclub.rabbitmq.socio-queue:socio.events.queue}")
    private String socioQueueName;

    private static final String VIDEOCLUB_DLX = "videoclub.events.dlx";
    private static final String SOCIO_DLQ = "socio.events.dlq";
    private static final String SOCIO_DLQ_ROUTING_KEY = "socio.dlq";

    @Bean
    public TopicExchange keycloakExchange() {
        // amq.topic is a pre-defined durable topic exchange in RabbitMQ
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue keycloakEventsQueue() {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Binding userEventsBinding(Queue keycloakEventsQueue, TopicExchange keycloakExchange) {
        return BindingBuilder.bind(keycloakEventsQueue)
                .to(keycloakExchange)
                .with("keycloak.user.#");
    }

    @Bean
    public Binding adminUserEventsBinding(Queue keycloakEventsQueue, TopicExchange keycloakExchange) {
        return BindingBuilder.bind(keycloakEventsQueue)
                .to(keycloakExchange)
                .with("keycloak.admin.USER.*");
    }

    // ------------------------------------------------------------------
    // Business exchange. The Socio bounded context only ever binds here,
    // so it can be extracted to its own service without touching the ACL.
    // ------------------------------------------------------------------

    @Bean
    public TopicExchange videoclubEventsExchange() {
        return new TopicExchange(videoclubExchangeName, true, false);
    }

    @Bean
    public Queue socioQueue() {
        return QueueBuilder.durable(socioQueueName)
                .deadLetterExchange(VIDEOCLUB_DLX)
                .deadLetterRoutingKey(SOCIO_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding socioEventsBinding(Queue socioQueue, TopicExchange videoclubEventsExchange) {
        return BindingBuilder.bind(socioQueue)
                .to(videoclubEventsExchange)
                .with("Socio.#");
    }

    // --- Poison message isolation ---

    @Bean
    public TopicExchange videoclubDeadLetterExchange() {
        return new TopicExchange(VIDEOCLUB_DLX, true, false);
    }

    @Bean
    public Queue socioDeadLetterQueue() {
        return QueueBuilder.durable(SOCIO_DLQ).build();
    }

    @Bean
    public Binding socioDeadLetterBinding(Queue socioDeadLetterQueue, TopicExchange videoclubDeadLetterExchange) {
        return BindingBuilder.bind(socioDeadLetterQueue)
                .to(videoclubDeadLetterExchange)
                .with(SOCIO_DLQ_ROUTING_KEY);
    }

    // ------------------------------------------------------------------

    /**
     * The Keycloak SPI publishes with {@code MessageProperties.PERSISTENT_TEXT_PLAIN}, so
     * messages arrive tagged as {@code text/plain} and the converter would refuse them.
     * Overriding the content type is a workaround on the consumer side; the clean fix is to
     * publish {@code application/json} from the SPI and delete this subclass.
     */
    @Bean
    public MessageConverter messageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper) {
            @Override
            public Object fromMessage(Message message, Object conversionHint) {
                forceJsonContentType(message);
                return super.fromMessage(message, conversionHint);
            }

            @Override
            public Object fromMessage(Message message) {
                forceJsonContentType(message);
                return super.fromMessage(message);
            }

            private void forceJsonContentType(Message message) {
                MessageProperties properties = message.getMessageProperties();
                if (properties != null) {
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                }
            }
        };
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    /**
     * Declaring this bean replaces the auto-configured one, which would drop every
     * {@code spring.rabbitmq.listener.simple.*} property on the floor — including the retry
     * policy. Running Boot's own configurer first applies that configuration, and only then
     * do we override what this application needs to pin explicitly.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);

        // A single consumer keeps queue ordering, which is what makes the
        // CREATE -> UPDATE -> DELETE sequence safe to apply as it arrives.
        factory.setConcurrentConsumers(1);

        // Do not requeue a rejected message: send it to the DLQ instead of spinning
        // forever on a payload that will never succeed. Transient failures are covered
        // by the retry policy configured in application.yml, which runs first.
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

}
