package ar.unrn.video.catalog;

import ar.unrn.video.catalog.mcp.MovieMcpTools;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.service.MovieService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP tools reach the services directly, below the REST layer that normally enforces the
 * permissions, so this test carries two obligations at once:
 *
 * <ol>
 *   <li>every tool is actually discoverable by the MCP tool provider, and</li>
 *   <li>no tool returns data to a caller lacking the matching authority.</li>
 * </ol>
 *
 * <p>Both obligations are met on the same methods: {@code @PreAuthorize} sits directly on the
 * {@code @McpTool} methods, which proxies the tool beans. That works only because the production
 * discovery path resolves the proxy's target class — see {@link MovieMcpTools} for the mechanism
 * and the risk. The two discovery tests below pin both sides of that bet, so a Spring AI upgrade
 * that changes either one produces a red build instead of a server with no tools.
 *
 * <p>A minimal context is used on purpose: method security is real, the services are mocked, and
 * no database, broker or Keycloak is needed.
 *
 * <p>The cross-domain denial cases (a catalog caller must not reach member data) moved to the
 * membership-service copy of this test when the services were split. What stays here is the
 * denial of a caller holding some unrelated authority.
 */
@SpringJUnitConfig(McpToolsSecurityTest.TestConfig.class)
class McpToolsSecurityTest {

    private static final MovieDTO A_MOVIE = new MovieDTO();

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        MovieService movieService() {
            MovieService movieService = mock(MovieService.class);
            when(movieService.findAll()).thenReturn(List.of(A_MOVIE));
            when(movieService.search("blade")).thenReturn(List.of(A_MOVIE));
            when(movieService.get(10001L)).thenReturn(A_MOVIE);
            return movieService;
        }

        @Bean
        MovieMcpTools movieMcpTools(MovieService movieService) {
            return new MovieMcpTools(movieService);
        }

    }

    @Autowired
    private MovieMcpTools movieMcpTools;

    @Test
    @DisplayName("every tool is discoverable through the path the running server uses")
    void everyToolIsDiscoverable() {
        // SyncMcpAnnotationProviders is what the Spring Boot autoconfiguration calls. Its providers
        // resolve AopUtils.getTargetClass(bean) before reading methods, which is the only reason
        // @PreAuthorize can sit on a @McpTool method. If a Spring AI upgrade drops that, the server
        // would boot advertising nothing at all; this assertion is what makes it fail loudly first.
        assertTrue(AopUtils.isAopProxy(movieMcpTools), "the tool bean is expected to be proxied");

        List<String> toolNames = SyncMcpAnnotationProviders
                .statelessToolSpecifications(List.of(movieMcpTools))
                .stream()
                .map(spec -> spec.tool().name())
                .sorted()
                .toList();

        // create_movie is not optional: CatalogSubAgent.CATALOG_TOOL_NAMES in videoclub-agent
        // declares it, and a tool missing here is silently dropped by that agent's name filter.
        assertEquals(
                List.of("create_movie", "get_movie", "list_movies", "search_movies"),
                toolNames);
    }

    @Test
    @DisplayName("the public provider API stays proxy-blind, which is the accepted risk")
    void publicProviderApiIsProxyBlind() {
        // Spring AI's public AbstractMcpToolProvider reads bean.getClass().getDeclaredMethods(),
        // so on a proxied tool bean it finds nothing. This is documented here rather than hidden:
        // it is the exact failure the server would suffer if the internal proxy-aware override in
        // SyncMcpAnnotationProviders ever went away. Should this assertion start failing because
        // the public API became proxy-aware, the risk is gone and this test can go with it.
        assertEquals(
                0,
                new SyncStatelessMcpToolProvider(List.of(movieMcpTools))
                        .getToolSpecifications()
                        .size());
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("movie tools allow a caller holding movie-permission-read")
    void movieToolsAllowMoviePermission() {
        assertEquals(1, movieMcpTools.listMovies().size());
        assertEquals(1, movieMcpTools.searchMovies("blade").size());
        assertEquals(A_MOVIE, movieMcpTools.getMovie(10001L));
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("movie tools deny a caller without movie-permission-read")
    void movieToolsDenyWithoutMoviePermission() {
        // The authority used here belongs to the other service on purpose: the split did not make
        // the token narrower, so a member-only caller still reaches this server and must be denied.
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.listMovies());
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.searchMovies("blade"));
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.getMovie(10001L));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("no tool is reachable by an anonymous caller")
    void toolsDenyAnonymousCallers() {
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.listMovies());
    }

    @Test
    @DisplayName("no tool is reachable with an empty security context")
    void toolsDenyMissingSecurityContext() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> movieMcpTools.listMovies());
    }

}
