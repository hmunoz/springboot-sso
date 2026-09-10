package ar.unrn.video.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${keycloak.rabbitmq.exchange:amq.topic}")
    private String exchangeName;

    @Value("${keycloak.rabbitmq.queue:keycloak-events}")
    private String queueName;

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

    @Bean
    public org.springframework.amqp.support.converter.MessageConverter messageConverter(tools.jackson.databind.json.JsonMapper jsonMapper) {
        org.springframework.amqp.support.converter.JacksonJsonMessageConverter converter =
                new org.springframework.amqp.support.converter.JacksonJsonMessageConverter(jsonMapper) {
                    @Override
                    public Object fromMessage(org.springframework.amqp.core.Message message, Object conversionHint) {
                        if (message.getMessageProperties() != null) {
                            message.getMessageProperties().setContentType("application/json");
                        }
                        return super.fromMessage(message, conversionHint);
                    }

                    @Override
                    public Object fromMessage(org.springframework.amqp.core.Message message) {
                        if (message.getMessageProperties() != null) {
                            message.getMessageProperties().setContentType("application/json");
                        }
                        return super.fromMessage(message);
                    }
                };
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    @Bean
    public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            org.springframework.amqp.support.converter.MessageConverter messageConverter) {
        org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory factory =
                new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
