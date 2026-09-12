package ar.unrn.video.mcp;

import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.service.MovieService;
import ar.unrn.video.util.NotFoundException;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools over the movie catalog.
 *
 * <p>{@code MovieService} carries no access control of its own — the REST layer guards it through
 * {@code MovieResource}. These tools reach the service directly, so the {@code @PreAuthorize} checks
 * below are the only thing standing between an authenticated token and the data.
 *
 * <h2>Why {@code @PreAuthorize} and {@code @McpTool} can share a method</h2>
 *
 * <p>Combining them is widely reported to break tool discovery, and the reasoning is half right:
 * {@code @PreAuthorize} makes Spring wrap this bean in a CGLIB proxy, Java does not copy method
 * annotations onto a subclass override, and Spring AI's public
 * {@code AbstractMcpToolProvider#doGetClassMethods} reads {@code bean.getClass().getDeclaredMethods()} —
 * which on that proxy yields overrides carrying no {@code @McpTool}.
 *
 * <p>The running server does not take that path. Tool registration goes through
 * {@code SyncMcpAnnotationProviders}, whose private provider subclasses override discovery with
 * {@code AnnotationProviderUtil#beanMethods}:
 *
 * <pre>{@code
 * ReflectionUtils.getUniqueDeclaredMethods(
 *     AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass());
 * }</pre>
 *
 * <p>That resolves the target class first, so the annotations are found. The methods then come from
 * the target class but are invoked on the proxy instance, and reflection dispatches virtually into
 * the CGLIB override where the security interceptor lives — so the check still runs.
 *
 * <h2>The bet this makes, and how to unwind it</h2>
 *
 * <p>That override is an implementation detail of an internal integration class, not a published
 * contract. If a future Spring AI release drops it, this server boots with <em>zero</em> tools and
 * no error in the log. {@code McpToolsSecurityTest#everyToolIsDiscoverable} exercises the exact
 * production path and is what turns that silent failure into a red build.
 *
 * <p>If it ever does break, the fix is to reintroduce the delegate: move every {@code @PreAuthorize}
 * onto a separate {@code @Component} (an {@code AuthorizedMovieQueries}) and leave this class
 * annotation-free so it is never proxied. See {@code docs/mcp-server.md} §4 for the full analysis.
 */
@Component
public class MovieMcpTools {

    private final MovieService movieService;

    public MovieMcpTools(final MovieService movieService) {
        this.movieService = movieService;
    }

    @McpTool(
            name = "list_movies",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "List movies",
            description = "Lists every movie in the VideoClub catalog, ordered by identifier."
    )
    @PreAuthorize("hasAuthority('movie-permission-read')")
    public List<MovieDTO> listMovies() {
        return movieService.findAll();
    }

    @McpTool(
            name = "get_movie",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "Get movie by id",
            description = "Returns a single movie by its identifier. Fails when no movie matches the id."
    )
    @PreAuthorize("hasAuthority('movie-permission-read')")
    public MovieDTO getMovie(
            @McpToolParam(description = "Identifier of the movie to retrieve", required = true)
            final Long id) {
        try {
            return movieService.get(id);
        } catch (NotFoundException ex) {
            // The REST layer turns a bare NotFoundException into a 404 and the message is never
            // read. Over MCP the exception message is all the agent gets back, so it has to say
            // something more useful than "null".
            throw new NotFoundException("No movie found with id " + id);
        }
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
    @PreAuthorize("hasAuthority('movie-permission-read')")
    public List<MovieDTO> searchMovies(
            @McpToolParam(description = "Text to match against the movie title", required = true)
            final String query) {
        return movieService.search(query == null ? "" : query);
    }

}
