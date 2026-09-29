package ar.unrn.video.membership.service;

import ar.unrn.video.membership.event.MoviePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * Consumer of the Movie replica. Bound to {@code movie.#}, so it receives both
 * {@code movie.created} and {@code movie.updated} without caring which is which
 * (see {@link MovieProjectionService#upsert}).
 *
 * <p>Exceptions propagate on purpose: Spring AMQP only acknowledges the message
 * when this method returns normally, so a failed upsert is retried and,
 * eventually, dead-lettered instead of being silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieEventListener {

    private final MovieProjectionService movieProjectionService;

    @RabbitListener(queues = "${videoclub.rabbitmq.movie-queue:membership.movie-events.queue}")
    public void onMovieEvent(final MoviePayload payload) {
        log.info("Received Movie event for movieId [{}]", payload.movieId());
        movieProjectionService.upsert(payload);
    }

}
