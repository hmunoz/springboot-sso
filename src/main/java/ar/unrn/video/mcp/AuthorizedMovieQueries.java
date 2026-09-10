package ar.unrn.video.mcp;

import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.service.MovieService;
import ar.unrn.video.util.NotFoundException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Authorization boundary for the movie MCP tools.
 *
 * <p>The checks cannot live on the {@code @McpTool} methods themselves: {@code @PreAuthorize}
 * wraps its bean in a CGLIB proxy, and the MCP tool provider collects tool methods with
 * {@code getClass().getDeclaredMethods()}, which on a proxy returns the generated overrides
 * without any annotation. An annotated tool class is therefore registered as an MCP bean but
 * contributes zero tools — the server boots with an empty tool list.
 *
 * <p>Keeping the authority checks on this separate, proxied delegate lets the tool classes stay
 * unproxied and discoverable while every call still passes a real method-security check.
 * {@code McpToolsSecurityTest} guards both halves of that arrangement.
 */
@Component
public class AuthorizedMovieQueries {

    private final MovieService movieService;

    public AuthorizedMovieQueries(final MovieService movieService) {
        this.movieService = movieService;
    }

    @PreAuthorize("hasAuthority('movie-permission-read')")
    public List<MovieDTO> findAll() {
        return movieService.findAll();
    }

    @PreAuthorize("hasAuthority('movie-permission-read')")
    public MovieDTO get(final Long id) {
        try {
            return movieService.get(id);
        } catch (NotFoundException ex) {
            // The REST layer turns a bare NotFoundException into a 404 and the message is never
            // read. Over MCP the exception message is all the agent gets back, so it has to say
            // something more useful than "null".
            throw new NotFoundException("No movie found with id " + id);
        }
    }

    @PreAuthorize("hasAuthority('movie-permission-read')")
    public List<MovieDTO> search(final String query) {
        return movieService.search(query);
    }

}
