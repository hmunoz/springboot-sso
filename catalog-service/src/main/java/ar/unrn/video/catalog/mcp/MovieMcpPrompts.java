package ar.unrn.video.catalog.mcp;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * MCP prompt exposing the procedure to create a movie without tripping the catalog's own
 * validations.
 *
 * <p>Every rule this prompt states is verified against the code that enforces it and nothing
 * else:
 *
 * <ul>
 *   <li>the title is unique ({@code Movie.title} carries {@code unique = true}, and
 *   {@code MovieDTO.title} is annotated {@code @MovieTitleUnique}, whose default message is
 *   {@code {Exists.movie.title}}), so searching with {@code search_movies} before creating avoids
 *   the 400 a duplicate would trigger;</li>
 *   <li>{@code genre} must be one of the {@link ar.unrn.video.catalog.domain.Genre} constants —
 *   reading {@code catalog://genres} first is cheaper than guessing;</li>
 *   <li>{@code price} is validated by {@code @DecimalMin("0.0")} on {@code MovieDTO.price} and
 *   stored with {@code scale = 2} on {@code Movie.price}, matching {@code MovieMcpTools#createMovie}'s
 *   own description ({@code "150.00"});</li>
 *   <li>{@code imageUrl} is optional: it carries no {@code @NotNull} on {@code MovieDTO}, only a
 *   {@code @Size(max = 1024)}.</li>
 * </ul>
 *
 * <h2>Why no {@code @PreAuthorize}</h2>
 *
 * <p>A prompt hands back a reusable procedure, not domain data — it tells the caller how to use
 * tools that are themselves authorized, but reveals nothing about the catalog on its own. Unlike
 * {@link MovieMcpResources}, which is injected as context before the model reasons and therefore
 * needs the same authority as the REST endpoint it mirrors, this method returns static
 * instructional text regardless of who is asking, so an authorization check here would protect
 * nothing. One consequence follows directly from that: with no {@code @PreAuthorize}, this bean
 * is never proxied, so the proxy-aware discovery bet documented on {@code MovieMcpTools} does not
 * even come into play — {@code SyncMcpAnnotationProviders} discovers the plain class directly.
 */
@Component
public class MovieMcpPrompts {

    @McpPrompt(
            name = "catalog-alta-pelicula",
            title = "Alta de pelicula",
            description = "Procedimiento para dar de alta una pelicula sin chocar contra las "
                    + "validaciones del catalogo.")
    public GetPromptResult altaPelicula(
            @McpArg(name = "titulo", description = "Titulo tentativo de la pelicula", required = true)
            final String titulo) {
        final String texto = """
                Vas a dar de alta la pelicula "%s" en el catalogo del VideoClub. Segui estos \
                pasos en orden:

                1. Busca primero con `search_movies` usando "%s" (o una parte del titulo). El \
                titulo es unico: si ya existe una pelicula con ese nombre (sin distinguir \
                mayusculas), `create_movie` devuelve un error 400.
                2. Lee el recurso `catalog://genres` antes de elegir un genero. El campo `genre` \
                solo acepta uno de esos valores exactos, o se puede omitir.
                3. `price` es opcional. Cuando se informa, es un decimal con dos posiciones, por \
                ejemplo "150.00", y no puede ser negativo.
                4. `imageUrl` es opcional; se puede omitir si no hay imagen de portada.
                5. Recien con esos datos confirmados, llama a `create_movie`.
                """.formatted(titulo, titulo);
        return new GetPromptResult(
                "Procedimiento de alta de pelicula",
                List.of(new PromptMessage(Role.USER, new TextContent(texto))));
    }

}
