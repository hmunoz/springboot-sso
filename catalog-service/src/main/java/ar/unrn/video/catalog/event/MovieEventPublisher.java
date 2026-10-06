package ar.unrn.video.catalog.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Relays {@link MovieDomainEvent} to the business exchange only after the
 * originating transaction has committed.
 *
 * <p>{@code AFTER_COMMIT} synchronization callbacks are best-effort: if the
 * broker is unreachable at this point, Spring logs the failure but the HTTP
 * call that triggered the write has already returned 200/201. Catalog does
 * not implement a transactional outbox for this exercise; accepting that gap
 * is exactly the tradeoff the plan calls out for the classroom scope, as
 * opposed to the reconciliation endpoint used for Socio (ADR-002).
 */
@Slf4j
@Component
public class MovieEventPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public MovieEventPublisher(final RabbitTemplate rabbitTemplate,
            @Value("${videoclub.rabbitmq.exchange:videoclub.events}") final String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMovieDomainEvent(final MovieDomainEvent event) {
        final String routingKey = event.eventType();
        final MoviePayload payload = new MoviePayload(event.movieId(), event.title(), event.price());
        final CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlation);

        try {
            final CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!confirm.ack()) {
                throw new AmqpException("Broker rejected domain event [%s]: %s"
                        .formatted(routingKey, confirm.reason()));
            }

            log.info("Published domain event [{}] to exchange [{}] for movie [{}]",
                    routingKey, exchange, event.movieId());
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
