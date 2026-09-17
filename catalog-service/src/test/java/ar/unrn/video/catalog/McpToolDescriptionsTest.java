package ar.unrn.video.catalog;

import ar.unrn.video.catalog.domain.Genre;
import ar.unrn.video.catalog.mcp.MovieMcpTools;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code catalog://genres} the single source of the accepted genres.
 *
 * <p>The {@code create_movie} descriptions point to that resource instead of listing the
 * {@link Genre} constants. Annotation attributes must be compile-time constants, so a list written
 * there could never be generated from the enum and would silently drift from it.
 *
 * <p>Accepted cost: an MCP client that only supports tools, such as the Claude API MCP connector,
 * does not learn the accepted values from the tool alone. The clients this project targets read the
 * resource: {@code CatalogSubAgent} injects it into the system prompt, and Claude Code exposes
 * resources to the model.
 */
class McpToolDescriptionsTest {

    private static final String GENRES_RESOURCE = "catalog://genres";

    @Test
    @DisplayName("the create_movie description points to catalog://genres instead of listing genres")
    void toolDescriptionReferencesGenresResource() {
        assertReferencesResourceWithoutListingGenres(createMovie().getAnnotation(McpTool.class).description());
    }

    @Test
    @DisplayName("the create_movie genre parameter points to catalog://genres instead of listing genres")
    void genreParameterReferencesGenresResource() {
        assertReferencesResourceWithoutListingGenres(
                createMovie().getParameters()[1].getAnnotation(McpToolParam.class).description());
    }

    private static void assertReferencesResourceWithoutListingGenres(final String description) {
        assertTrue(description.contains(GENRES_RESOURCE),
                () -> "Expected a reference to " + GENRES_RESOURCE + " in: " + description);
        for (final Genre genre : Genre.values()) {
            assertFalse(description.contains(genre.name()),
                    () -> "Genre " + genre.name() + " is hardcoded again; reference " + GENRES_RESOURCE
                            + " instead: " + description);
        }
    }

    private static Method createMovie() {
        try {
            return MovieMcpTools.class.getMethod("createMovie", String.class, String.class, String.class, String.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("create_movie changed its signature; update this guard", ex);
        }
    }

}
