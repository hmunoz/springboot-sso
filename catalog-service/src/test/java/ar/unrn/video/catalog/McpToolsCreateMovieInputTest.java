package ar.unrn.video.catalog;

import ar.unrn.video.catalog.domain.Genre;
import ar.unrn.video.catalog.mcp.MovieMcpTools;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.service.MovieService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code create_movie} must reject an invalid genre or price instead of creating the movie without
 * it. Over MCP the exception message is the whole error the client receives, so it has to name the
 * valid values: the tool description only points to {@code catalog://genres}, and a client that
 * never reads resources has no other way to learn them.
 *
 * <p>The tools are built directly, so no proxy or authorization is involved.
 */
class McpToolsCreateMovieInputTest {

    private final MovieService movieService = mock(MovieService.class);
    private final MovieMcpTools tools = new MovieMcpTools(movieService);

    @Test
    @DisplayName("an unknown genre is rejected with every valid genre in the message")
    void unknownGenreIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tools.createMovie("Blade Runner", "Sci-Fi", null, null));

        assertTrue(ex.getMessage().startsWith("Unknown genre 'Sci-Fi'"), ex.getMessage());
        for (Genre genre : Genre.values()) {
            assertTrue(ex.getMessage().contains(genre.name()), ex.getMessage());
        }
        verify(movieService, never()).create(any());
    }

    @Test
    @DisplayName("an unparseable price is rejected instead of being dropped")
    void invalidPriceIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tools.createMovie("Blade Runner", null, "ciento cincuenta", null));

        assertEquals("Invalid price 'ciento cincuenta'. Use a decimal string such as '150.00'", ex.getMessage());
        verify(movieService, never()).create(any());
    }

    @Test
    @DisplayName("a valid genre is accepted regardless of case, and blank optional values stay null")
    void validInputIsCreated() {
        when(movieService.create(any())).thenReturn(1L);
        ArgumentCaptor<MovieDTO> created = ArgumentCaptor.forClass(MovieDTO.class);

        assertEquals(1L, tools.createMovie("Blade Runner", " science_fiction ", " ", null));

        verify(movieService).create(created.capture());
        assertEquals(Genre.SCIENCE_FICTION, created.getValue().getGenre());
        assertNull(created.getValue().getPrice());
    }

}
