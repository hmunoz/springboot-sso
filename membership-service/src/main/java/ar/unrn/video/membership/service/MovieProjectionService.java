package ar.unrn.video.membership.service;

import ar.unrn.video.membership.domain.MovieProjection;
import ar.unrn.video.membership.event.MoviePayload;
import ar.unrn.video.membership.repos.MovieProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Keeps {@code movie_projection} in sync with catalog's Movie aggregate.
 *
 * <p>Both {@code movie.created} and {@code movie.updated} land here as the same
 * upsert: the projection only mirrors current state, so there is no reason to
 * branch on which routing key delivered it. That also makes the operation
 * idempotent under RabbitMQ's at-least-once delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieProjectionService {

    private final MovieProjectionRepository movieProjectionRepository;

    @Transactional
    public void upsert(final MoviePayload payload) {
        final MovieProjection projection = movieProjectionRepository.findById(payload.movieId())
                .orElseGet(() -> {
                    final MovieProjection nueva = new MovieProjection();
                    nueva.setId(payload.movieId());
                    return nueva;
                });

        projection.setTitle(payload.title());
        projection.setPrice(payload.price());
        projection.setUpdatedAt(LocalDateTime.now());

        movieProjectionRepository.save(projection);
        log.info("Movie projection upserted: id [{}], title [{}]", payload.movieId(), payload.title());
    }

}
