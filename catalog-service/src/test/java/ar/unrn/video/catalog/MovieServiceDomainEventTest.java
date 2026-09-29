package ar.unrn.video.catalog;

import ar.unrn.video.catalog.domain.Genre;
import ar.unrn.video.catalog.domain.Movie;
import ar.unrn.video.catalog.event.MovieDomainEvent;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.repos.MovieRepository;
import ar.unrn.video.catalog.service.MovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MovieService.create()/update() must publish a {@link MovieDomainEvent} after
 * {@code save()}, inside the same transaction that MovieEventPublisher relays
 * to RabbitMQ only after commit (see docs/plan/plan-carrito-ecst-rabbitmq.md,
 * Fase 1).
 */
class MovieServiceDomainEventTest {

    private MovieRepository movieRepository;
    private ApplicationEventPublisher applicationEventPublisher;
    private MovieService movieService;

    @BeforeEach
    void setUp() {
        movieRepository = mock(MovieRepository.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        movieService = new MovieService(movieRepository, applicationEventPublisher);
    }

    private MovieDTO dto() {
        MovieDTO dto = new MovieDTO();
        dto.setTitle("Blade Runner");
        dto.setGenre(Genre.SCIENCE_FICTION);
        dto.setPrice(new BigDecimal("9.99"));
        return dto;
    }

    @Test
    @DisplayName("create() publishes a movie.created event carrying the saved state")
    void createPublishesCreatedEvent() {
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie saved = invocation.getArgument(0);
            saved.setId(10001L);
            return saved;
        });

        movieService.create(dto());

        ArgumentCaptor<MovieDomainEvent> captor = ArgumentCaptor.forClass(MovieDomainEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        MovieDomainEvent event = captor.getValue();
        assertEquals(10001L, event.movieId());
        assertEquals("Blade Runner", event.title());
        assertEquals(new BigDecimal("9.99"), event.price());
        assertEquals(MovieDomainEvent.CREATED, event.eventType());
    }

    @Test
    @DisplayName("update() publishes a movie.updated event carrying the saved state")
    void updatePublishesUpdatedEvent() {
        Movie existing = new Movie();
        existing.setId(10001L);
        existing.setTitle("Old Title");
        existing.setPrice(new BigDecimal("5.00"));

        when(movieRepository.findById(10001L)).thenReturn(Optional.of(existing));
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));

        movieService.update(10001L, dto());

        ArgumentCaptor<MovieDomainEvent> captor = ArgumentCaptor.forClass(MovieDomainEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        MovieDomainEvent event = captor.getValue();
        assertEquals(10001L, event.movieId());
        assertEquals("Blade Runner", event.title());
        assertEquals(new BigDecimal("9.99"), event.price());
        assertEquals(MovieDomainEvent.UPDATED, event.eventType());
    }

}
