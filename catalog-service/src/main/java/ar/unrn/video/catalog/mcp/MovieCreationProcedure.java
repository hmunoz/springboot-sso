package ar.unrn.video.catalog.mcp;

/**
 * Single source of the movie creation procedure steps.
 *
 * <p>{@link MovieMcpResources} publishes {@link #steps()} verbatim as the resource
 * {@code catalog://procedures/movie-creation}, injected by the agent as context before the model
 * reasons. {@link MovieMcpPrompts} renders the exact same steps, prefixed with an intro that names
 * a concrete {@code titulo}, as the prompt {@code catalog-alta-pelicula}, for a user-chosen flow.
 * Keeping the steps here, in exactly one place, is what stops the resource and the prompt from
 * drifting apart the way the resource and the agent's own hardcoded sentence once did.
 *
 * <p>The steps intentionally do not depend on {@code titulo}: the resource has no arguments, so
 * nothing here can reference a specific title. Step 1 sends the caller to search using whatever
 * title they have in hand instead.
 */
final class MovieCreationProcedure {

    private MovieCreationProcedure() {
    }

    static String steps() {
        return """
                1. Busca primero con `search_movies` usando el titulo (o una parte de el). El \
                titulo es unico: si ya existe una pelicula con ese nombre (sin distinguir \
                mayusculas), `create_movie` devuelve un error 400.
                2. Lee el recurso `catalog://genres` antes de elegir un genero. El campo `genre` \
                solo acepta uno de esos valores exactos, o se puede omitir.
                3. `price` es opcional. Cuando se informa, es un decimal con dos posiciones, por \
                ejemplo "150.00", y no puede ser negativo.
                4. `imageUrl` es opcional; se puede omitir si no hay imagen de portada.
                5. Recien con esos datos confirmados, llama a `create_movie`.
                """;
    }

}
