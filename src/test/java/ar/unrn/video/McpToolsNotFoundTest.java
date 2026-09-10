package ar.unrn.video;

import ar.unrn.video.mcp.AuthorizedMovieQueries;
import ar.unrn.video.mcp.AuthorizedSocioQueries;
import ar.unrn.video.service.MovieService;
import ar.unrn.video.service.SocioService;
import ar.unrn.video.util.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Over MCP the exception message is the entire error the agent receives; a bare
 * {@link NotFoundException} would reach it as "null".
 */
class McpToolsNotFoundTest {

    @Test
    @DisplayName("an unknown movie id produces an actionable message")
    void unknownMovieId() {
        MovieService movieService = mock(MovieService.class);
        when(movieService.get(99999L)).thenThrow(new NotFoundException());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> new AuthorizedMovieQueries(movieService).get(99999L));

        assertEquals("No movie found with id 99999", ex.getMessage());
    }

    @Test
    @DisplayName("an unknown member id produces an actionable message")
    void unknownSocioId() {
        SocioService socioService = mock(SocioService.class);
        when(socioService.getById(99999L)).thenThrow(new NotFoundException());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> new AuthorizedSocioQueries(socioService).getById(99999L));

        assertEquals("No member found with id 99999", ex.getMessage());
    }

}
