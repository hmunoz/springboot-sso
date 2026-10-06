package ar.unrn.video.membership;

import ar.unrn.video.membership.domain.MovieProjection;
import ar.unrn.video.membership.event.MoviePayload;
import ar.unrn.video.membership.repos.MovieProjectionRepository;
import ar.unrn.video.membership.service.MovieProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MovieProjectionService.upsert() must behave the same way whether it is
 * called from a {@code movie.created} or a {@code movie.updated} routing key:
 * the projection only mirrors current state (see
 * docs/plan/plan-carrito-ecst-rabbitmq.md, Fase 3).
 */
class MovieProjectionServiceTest {

    private static final Long MOVIE_ID = 10001L;

    private MovieProjectionRepository movieProjectionRepository;
    private MovieProjectionService movieProjectionService;

    @BeforeEach
    void setUp() {
        movieProjectionRepository = mock(MovieProjectionRepository.class);
        movieProjectionService = new MovieProjectionService(movieProjectionRepository);
    }

    private MoviePayload payload(String title, String price) {
        return new MoviePayload(MOVIE_ID, title, new BigDecimal(price));
    }

    @Test
    @DisplayName("upsert() creates the projection with the event's id when it does not exist yet")
    void upsertCreatesWhenAbsent() {
        when(movieProjectionRepository.findById(MOVIE_ID)).thenReturn(Optional.empty());

        movieProjectionService.upsert(payload("Blade Runner", "9.99"));

        ArgumentCaptor<MovieProjection> captor = ArgumentCaptor.forClass(MovieProjection.class);
        verify(movieProjectionRepository).save(captor.capture());

        MovieProjection saved = captor.getValue();
        assertEquals(MOVIE_ID, saved.getId());
        assertEquals("Blade Runner", saved.getTitle());
        assertEquals(new BigDecimal("9.99"), saved.getPrice());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("upsert() refreshes an existing projection in place, preserving its id")
    void upsertUpdatesInPlace() {
        MovieProjection existing = new MovieProjection();
        existing.setId(MOVIE_ID);
        existing.setTitle("Old Title");
        existing.setPrice(new BigDecimal("5.00"));

        when(movieProjectionRepository.findById(MOVIE_ID)).thenReturn(Optional.of(existing));

        movieProjectionService.upsert(payload("New Title", "12.50"));

        ArgumentCaptor<MovieProjection> captor = ArgumentCaptor.forClass(MovieProjection.class);
        verify(movieProjectionRepository).save(captor.capture());

        MovieProjection saved = captor.getValue();
        assertEquals(MOVIE_ID, saved.getId());
        assertEquals("New Title", saved.getTitle());
        assertEquals(new BigDecimal("12.50"), saved.getPrice());
    }

}
