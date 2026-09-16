package ar.unrn.video.catalog.mcp;

import ar.unrn.video.catalog.domain.Genre;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.service.MovieService;
import ar.unrn.video.catalog.util.NotFoundException;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * MCP resources over the movie catalog: Markdown context injected by the application before it
 * talks to the model, as opposed to {@link MovieMcpTools}, whose data the model asks for through
 * a tool call once it decides it needs it.
 *
 * <h2>Why {@code movie_card} is not a duplicate of {@code get_movie}</h2>
 *
 * <p>Both surface the same fields of the same {@code MovieDTO}. What differs is who decides to
 * fetch them and when. {@code get_movie} returns JSON that the model parses after it reasoned
 * that it needed a specific movie, at the cost of a tool-calling round trip. {@code movie_card}
 * returns prose that the application can inject as context before it ever prompts the model, at
 * no round-trip cost at all. Same data, different representation and a different consumer — that
 * distinction is the entire reason this class exists next to {@link MovieMcpTools} instead of
 * being folded into it.
 *
 * <h2>Why {@code @PreAuthorize} matters more here than on a tool</h2>
 *
 * <p>A tool is only ever invoked after the model — acting on behalf of an authenticated caller —
 * decided to call it. A resource can be read by the application itself, ahead of any reasoning,
 * simply because it exists. Without {@code @PreAuthorize} here, an application that injects
 * {@code catalog://movies/{id}} as context would leak catalog data to a caller who could not read
 * it through {@code MovieResource} either. The authority required is exactly the REST endpoint's:
 * {@code movie-permission-read}.
 *
 * <p>Putting {@code @PreAuthorize} on a {@code @McpResource} method proxies this bean the same
 * way it proxies {@link MovieMcpTools}, and depends on the same proxy-aware discovery path in
 * {@code SyncMcpAnnotationProviders}. See {@link MovieMcpTools} for the full mechanism, the risk
 * if a future Spring AI release drops it, and how to unwind it.
 */
@Component
public class MovieMcpResources {

    private final MovieService movieService;

    public MovieMcpResources(final MovieService movieService) {
        this.movieService = movieService;
    }

    @McpResource(
            uri = "catalog://genres",
            name = "catalog_genres",
            title = "Generos del catalogo",
            description = "Listado en Markdown de los generos validos para una pelicula, generado "
                    + "a partir del enum Genre.",
            mimeType = "text/markdown")
    @PreAuthorize("hasAuthority('movie-permission-read')")
    public String genres() {
        // Generated from Genre.values() on every call, never hardcoded: adding a constant to the
        // enum updates this resource for free, and there is no separate list to fall out of sync.
        final StringBuilder markdown = new StringBuilder("# Generos del catalogo\n\n");
        for (final Genre genre : Genre.values()) {
            markdown.append("- ").append(genre.name()).append('\n');
        }
        return markdown.toString();
    }

    @McpResource(
            uri = "catalog://movies/{id}",
            name = "movie_card",
            title = "Ficha de pelicula",
            description = "Ficha de una pelicula del catalogo en Markdown, lista para inyectar "
                    + "como contexto.",
            mimeType = "text/markdown")
    @PreAuthorize("hasAuthority('movie-permission-read')")
    public String movieCard(final String id) {
        final Long movieId = parseId(id);
        final MovieDTO movie;
        try {
            movie = movieService.get(movieId);
        } catch (NotFoundException ex) {
            // See MovieMcpTools#getMovie: over MCP the exception message is the whole error.
            throw new NotFoundException("No movie found with id " + id);
        }
        return render(movie);
    }

    private static Long parseId(final String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            // The {id} template variable binds as a String (see McpResource template binding), so
            // a non-numeric id never reaches MovieService at all; it has to be rejected here with
            // a message that names the offending value instead of surfacing a bare parse failure.
            throw new NotFoundException("Invalid movie id: " + id);
        }
    }

    private static String render(final MovieDTO movie) {
        // Only fields that exist on MovieDTO: title, genre, price, imageUrl. price and imageUrl
        // are nullable on the DTO, so both are rendered defensively.
        final StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(movie.getTitle()).append("\n\n");
        markdown.append("- **Genero:** ")
                .append(movie.getGenre() != null ? movie.getGenre().name() : "sin especificar")
                .append('\n');
        markdown.append("- **Precio de alquiler:** ")
                .append(movie.getPrice() != null ? movie.getPrice().toPlainString() : "sin especificar")
                .append('\n');
        markdown.append("- **Imagen:** ")
                .append(movie.getImageUrl() != null ? movie.getImageUrl() : "sin especificar")
                .append('\n');
        return markdown.toString();
    }

}
