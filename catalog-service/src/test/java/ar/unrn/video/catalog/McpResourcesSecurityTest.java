package ar.unrn.video.catalog;

import ar.unrn.video.catalog.domain.Genre;
import ar.unrn.video.catalog.mcp.MovieMcpPrompts;
import ar.unrn.video.catalog.mcp.MovieMcpResources;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.service.MovieService;
import ar.unrn.video.catalog.util.NotFoundException;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@link McpToolsSecurityTest}, but for {@link MovieMcpResources}: discovery through the
 * proxy-aware path the running server uses, authorization, content, and the actionable-message
 * requirement for invalid and unknown ids.
 *
 * <p>A minimal context is used on purpose: method security is real, {@code MovieService} is
 * mocked, and no database, broker or Keycloak is needed.
 */
@SpringJUnitConfig(McpResourcesSecurityTest.TestConfig.class)
class McpResourcesSecurityTest {

    private static final MovieDTO A_MOVIE = new MovieDTO();

    static {
        A_MOVIE.setTitle("Blade Runner");
        A_MOVIE.setGenre(Genre.SCIENCE_FICTION);
        A_MOVIE.setPrice(new BigDecimal("150.00"));
        A_MOVIE.setImageUrl("https://example.org/blade-runner.jpg");
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        MovieService movieService() {
            MovieService movieService = mock(MovieService.class);
            when(movieService.get(10001L)).thenReturn(A_MOVIE);
            when(movieService.get(99999L)).thenThrow(new NotFoundException());
            return movieService;
        }

        @Bean
        MovieMcpResources movieMcpResources(MovieService movieService) {
            return new MovieMcpResources(movieService);
        }

    }

    @Autowired
    private MovieMcpResources movieMcpResources;

    @Test
    @DisplayName("every resource is discoverable through the path the running server uses")
    void everyResourceIsDiscoverable() {
        // Same bet as MovieMcpTools: SyncMcpAnnotationProviders resolves AopUtils.getTargetClass
        // before reading methods, which is the only reason @PreAuthorize can sit on a
        // @McpResource method. A fixed uri and a {id} template live in separate specification
        // lists, so both are asserted independently.
        assertTrue(AopUtils.isAopProxy(movieMcpResources), "the resource bean is expected to be proxied");

        List<String> fixedUris = SyncMcpAnnotationProviders
                .statelessResourceSpecifications(List.of(movieMcpResources))
                .stream()
                .map(spec -> spec.resource().uri())
                .sorted()
                .toList();
        assertEquals(List.of("catalog://genres", "catalog://procedures/movie-creation"), fixedUris);

        List<String> templateUris = SyncMcpAnnotationProviders
                .statelessResourceTemplateSpecifications(List.of(movieMcpResources))
                .stream()
                .map(spec -> spec.resourceTemplate().uriTemplate())
                .sorted()
                .toList();
        assertEquals(List.of("catalog://movies/{id}"), templateUris);
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("resources allow a caller holding movie-permission-read and render the real data")
    void resourcesAllowMoviePermission() {
        String genres = movieMcpResources.genres();
        for (Genre genre : Genre.values()) {
            assertTrue(genres.contains(genre.name()), () -> "genres resource is missing " + genre);
        }

        String card = movieMcpResources.movieCard("10001");
        assertTrue(card.contains("Blade Runner"));
        assertTrue(card.contains("SCIENCE_FICTION"));
        assertTrue(card.contains("150.00"));
        assertTrue(card.contains("https://example.org/blade-runner.jpg"));
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("resources deny a caller without movie-permission-read")
    void resourcesDenyWithoutMoviePermission() {
        // The authority used belongs to the other service on purpose, same as McpToolsSecurityTest.
        assertThrows(AccessDeniedException.class, () -> movieMcpResources.genres());
        assertThrows(AccessDeniedException.class, () -> movieMcpResources.movieCard("10001"));
        assertThrows(AccessDeniedException.class, () -> movieMcpResources.movieCreationProcedure());
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("the movie-creation procedure resource allows a caller holding movie-permission-read and renders the key rules")
    void procedureResourceAllowsMoviePermission() {
        String procedure = movieMcpResources.movieCreationProcedure();
        assertTrue(procedure.contains("search_movies"));
        assertTrue(procedure.contains("catalog://genres"));
        assertTrue(procedure.contains("create_movie"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("the movie-creation procedure resource is not reachable by an anonymous caller")
    void procedureResourceDeniesAnonymousCallers() {
        assertThrows(AccessDeniedException.class, () -> movieMcpResources.movieCreationProcedure());
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("the procedure resource and the alta-pelicula prompt render the exact same steps")
    void procedureResourceAndPromptShareTheSameSteps() {
        // The regression anchor for the single-source-of-truth requirement: MovieMcpResources and
        // MovieMcpPrompts must never diverge again the way the resource and the agent's old
        // hardcoded sentence did. Both render MovieCreationProcedure#steps() verbatim, so the text
        // starting at step 1 must be byte-identical in the resource and in the prompt.
        String resourceText = movieMcpResources.movieCreationProcedure();
        String promptText = textOf(new MovieMcpPrompts().altaPelicula("Cualquiera"));

        String resourceSteps = resourceText.substring(resourceText.indexOf("1. Busca"));
        String promptSteps = promptText.substring(promptText.indexOf("1. Busca"));
        assertEquals(resourceSteps, promptSteps);
    }

    private static String textOf(GetPromptResult result) {
        PromptMessage message = result.messages().get(0);
        return ((TextContent) message.content()).text();
    }

    @Test
    @WithAnonymousUser
    @DisplayName("no resource is reachable by an anonymous caller")
    void resourcesDenyAnonymousCallers() {
        assertThrows(AccessDeniedException.class, () -> movieMcpResources.genres());
    }

    @Test
    @DisplayName("no resource is reachable with an empty security context")
    void resourcesDenyMissingSecurityContext() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> movieMcpResources.genres());
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("an unknown movie id produces an actionable message")
    void unknownMovieId() {
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> movieMcpResources.movieCard("99999"));
        assertEquals("No movie found with id 99999", ex.getMessage());
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("a non-numeric movie id produces an actionable message")
    void invalidMovieId() {
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> movieMcpResources.movieCard("not-a-number"));
        assertTrue(ex.getMessage().contains("not-a-number"));
    }

}
