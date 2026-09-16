package ar.unrn.video.catalog;

import ar.unrn.video.catalog.mcp.MovieMcpPrompts;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.aop.support.AopUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MovieMcpPrompts} carries no {@code @PreAuthorize}, so it is built directly here, exactly
 * like {@link McpToolsNotFoundTest} builds {@code MovieMcpTools} directly: no proxy is involved
 * and no Spring context is needed.
 */
class McpPromptsTest {

    private final MovieMcpPrompts prompts = new MovieMcpPrompts();

    @Test
    @DisplayName("the prompt is discoverable through the path the running server uses, and the bean is never proxied")
    void promptIsDiscoverable() {
        // Without @PreAuthorize, Spring never wraps this bean in a proxy in the first place —
        // unlike MovieMcpResources, whose discovery depends on resolving the proxy's target class.
        assertFalse(AopUtils.isAopProxy(prompts), "a prompt bean carries no @PreAuthorize and is never proxied");

        List<String> promptNames = SyncMcpAnnotationProviders
                .statelessPromptSpecifications(List.of(prompts))
                .stream()
                .map(spec -> spec.prompt().name())
                .toList();
        assertEquals(List.of("catalog-alta-pelicula"), promptNames);
    }

    @Test
    @DisplayName("the titulo argument is substituted into the returned procedure")
    void tituloIsSubstituted() {
        String text = textOf(prompts.altaPelicula("Matrix Reloaded"));
        assertTrue(text.contains("Matrix Reloaded"));
    }

    @Test
    @DisplayName("the procedure encodes only rules the catalog service actually enforces")
    void encodesTheRealRules() {
        String text = textOf(prompts.altaPelicula("Cualquiera"));

        // Rule 1: search before create, because the title is unique.
        assertTrue(text.contains("search_movies"));
        assertTrue(text.contains("unico"));

        // Rule 2: genre must come from the enum, read catalog://genres first.
        assertTrue(text.contains("catalog://genres"));
        assertTrue(text.contains("genre"));

        // Rule 3: price format.
        assertTrue(text.contains("150.00"));

        // Rule 4: imageUrl is optional.
        assertTrue(text.contains("imageUrl"));

        assertTrue(text.contains("create_movie"));
    }

    private static String textOf(GetPromptResult result) {
        PromptMessage message = result.messages().get(0);
        return ((TextContent) message.content()).text();
    }

}
