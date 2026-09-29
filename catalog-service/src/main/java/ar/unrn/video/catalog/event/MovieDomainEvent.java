package ar.unrn.video.catalog.event;

import java.math.BigDecimal;

/**
 * In-memory Spring event published from {@code MovieService} right after
 * {@code movieRepository.save()}, inside the same transaction.
 *
 * <p>It never reaches the broker directly: {@code MovieEventPublisher} only
 * relays it to RabbitMQ once the surrounding transaction has committed
 * ({@code @TransactionalEventListener(phase = AFTER_COMMIT)}), which is what
 * rules out the phantom-event antipattern described in the plan.
 */
public record MovieDomainEvent(Long movieId, String title, BigDecimal price, String eventType) {

    public static final String CREATED = "movie.created";
    public static final String UPDATED = "movie.updated";

}
