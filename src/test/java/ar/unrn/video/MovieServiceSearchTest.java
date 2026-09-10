package ar.unrn.video;

import ar.unrn.video.domain.Movie;
import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.repos.MovieRepository;
import ar.unrn.video.service.MovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieServiceSearchTest {

    private MovieRepository movieRepository;
    private MovieService movieService;

    @BeforeEach
    void setUp() {
        movieRepository = mock(MovieRepository.class);
        movieService = new MovieService(movieRepository);
    }

    private Movie movie(Long id, String title) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle(title);
        return movie;
    }

    @Test
    @DisplayName("search delegates to a case-insensitive partial match ordered by id")
    void searchDelegatesToPartialMatch() {
        when(movieRepository.findByTitleContainingIgnoreCase(anyString(), any(Sort.class)))
                .thenReturn(List.of(movie(10001L, "Blade Runner")));

        List<MovieDTO> result = movieService.search("blade");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(movieRepository).findByTitleContainingIgnoreCase(query.capture(), sort.capture());

        assertEquals("blade", query.getValue());
        assertEquals(Sort.by("id"), sort.getValue());
        assertEquals(1, result.size());
        assertEquals(10001L, result.getFirst().getId());
        assertEquals("Blade Runner", result.getFirst().getTitle());
    }

    @Test
    @DisplayName("search returns an empty list when nothing matches")
    void searchReturnsEmptyList() {
        when(movieRepository.findByTitleContainingIgnoreCase(anyString(), any(Sort.class)))
                .thenReturn(List.of());

        assertTrue(movieService.search("no-such-title").isEmpty());
    }

    @Test
    @DisplayName("search maps every entity to its DTO")
    void searchMapsEveryEntity() {
        when(movieRepository.findByTitleContainingIgnoreCase(anyString(), any(Sort.class)))
                .thenReturn(List.of(movie(1L, "Alien"), movie(2L, "Aliens")));

        List<MovieDTO> result = movieService.search("alien");

        assertEquals(List.of("Alien", "Aliens"), result.stream().map(MovieDTO::getTitle).toList());
    }

}
