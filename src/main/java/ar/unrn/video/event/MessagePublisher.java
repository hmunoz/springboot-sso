package ar.unrn.video.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes canonical domain events to the business exchange.
 *
 * <p>Publishing is confirmed synchronously on purpose. This class is called from inside a
 * {@code @RabbitListener}, and if the publish silently failed after the inbound message
 * had already been acknowledged, the event would vanish with no trace. Waiting for the
 * broker confirmation lets the failure propagate so the inbound message is not acked.
 *
 * <p>Requires {@code spring.rabbitmq.publisher-confirm-type: correlated}.
 */
@Slf4j
@Service
public class MessagePublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public MessagePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${videoclub.rabbitmq.exchange:videoclub.events}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    public <K, T> void publish(Event<K, T> event) {
        String routingKey = event.routingKey();
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend(exchange, routingKey, event, correlation);

        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                throw new AmqpException("Broker rejected domain event [%s]: %s"
                        .formatted(routingKey, confirm.reason()));
            }

            log.info("Published domain event [{}] to exchange [{}]", routingKey, exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while confirming domain event [" + routingKey + "]", e);
        } catch (AmqpException e) {
            throw e;
        } catch (Exception e) {
            throw new AmqpException("Could not confirm domain event [" + routingKey + "]", e);
        }
    }

}
