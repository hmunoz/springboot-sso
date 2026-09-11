package ar.unrn.video.mcp;

import ar.unrn.video.model.MovieDTO;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools over the movie catalog.
 *
 * <p>This class must stay free of any annotation that triggers proxying ({@code @PreAuthorize},
 * {@code @Transactional}, {@code @Cacheable}, ...): the MCP tool provider reads tool methods from
 * {@code getClass().getDeclaredMethods()}, and a proxy hides them, silently leaving the server
 * with no tools. Authorization therefore lives in {@link AuthorizedMovieQueries}.
 */
@Component
public class MovieMcpTools {

    private final AuthorizedMovieQueries movies;

    public MovieMcpTools(final AuthorizedMovieQueries movies) {
        this.movies = movies;
    }

    @McpTool(
            name = "list_movies",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "List movies",
            description = "Lists every movie in the VideoClub catalog, ordered by identifier."
    )
    public List<MovieDTO> listMovies() {
        return movies.findAll();
    }

    @McpTool(
            name = "get_movie",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "Get movie by id",
            description = "Returns a single movie by its identifier. Fails when no movie matches the id."
    )
    public MovieDTO getMovie(
            @McpToolParam(description = "Identifier of the movie to retrieve", required = true)
            final Long id) {
        return movies.get(id);
    }

    @McpTool(
            name = "search_movies",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "Search movies by title",
            description = "Searches movies whose title contains the given text, ignoring case. "
                    + "The catalog only stores identifier and title, so no other field is searchable."
    )
    public List<MovieDTO> searchMovies(
            @McpToolParam(description = "Text to match against the movie title", required = true)
            final String query) {
        return movies.search(query == null ? "" : query);
    }

}
