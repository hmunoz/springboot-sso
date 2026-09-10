package ar.unrn.video;

import ar.unrn.video.mcp.AuthorizedMovieQueries;
import ar.unrn.video.mcp.AuthorizedSocioQueries;
import ar.unrn.video.mcp.MovieMcpTools;
import ar.unrn.video.mcp.SocioMcpTools;
import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.service.MovieService;
import ar.unrn.video.service.SocioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;
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
 * <p>Both matter together, because the obvious way to satisfy the second one — putting
 * {@code @PreAuthorize} straight on the {@code @McpTool} methods — silently breaks the first.
 *
 * <p>A minimal context is used on purpose: method security is real, the services are mocked, and
 * no database, broker or Keycloak is needed.
 */
@SpringJUnitConfig(McpToolsSecurityTest.TestConfig.class)
class McpToolsSecurityTest {

    private static final MovieDTO A_MOVIE = new MovieDTO();
    private static final SocioDTO A_SOCIO =
            new SocioDTO(10001L, "kc-id", "socio@unrn.edu.ar", "socio", "Juan", "Perez", true, null, null);

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
        SocioService socioService() {
            SocioService socioService = mock(SocioService.class);
            when(socioService.findAll()).thenReturn(List.of(A_SOCIO));
            when(socioService.getById(10001L)).thenReturn(A_SOCIO);
            return socioService;
        }

        @Bean
        AuthorizedMovieQueries authorizedMovieQueries(MovieService movieService) {
            return new AuthorizedMovieQueries(movieService);
        }

        @Bean
        AuthorizedSocioQueries authorizedSocioQueries(SocioService socioService) {
            return new AuthorizedSocioQueries(socioService);
        }

        @Bean
        MovieMcpTools movieMcpTools(AuthorizedMovieQueries movies) {
            return new MovieMcpTools(movies);
        }

        @Bean
        SocioMcpTools socioMcpTools(AuthorizedSocioQueries socios) {
            return new SocioMcpTools(socios);
        }

    }

    @Autowired
    private MovieMcpTools movieMcpTools;

    @Autowired
    private SocioMcpTools socioMcpTools;

    @Test
    @DisplayName("every tool is discoverable as a Spring bean")
    void everyToolIsDiscoverable() {
        // The tool provider reads methods off getClass().getDeclaredMethods(). Adding any
        // proxy-triggering annotation to the tool classes empties this list, and the MCP server
        // would then boot with no tools at all. This assertion is the guard against that.
        List<String> toolNames = new SyncStatelessMcpToolProvider(List.of(movieMcpTools, socioMcpTools))
                .getToolSpecifications()
                .stream()
                .map(spec -> spec.tool().name())
                .sorted()
                .toList();

        assertEquals(
                List.of("get_movie", "get_socio", "list_movies", "list_socios", "search_movies"),
                toolNames);
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
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.listMovies());
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.searchMovies("blade"));
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.getMovie(10001L));
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("member tools allow a caller holding socio-permission-read")
    void socioToolsAllowSocioPermission() {
        assertEquals(1, socioMcpTools.listSocios().size());
        assertEquals(A_SOCIO, socioMcpTools.getSocio(10001L));
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("member tools deny a caller without socio-permission-read")
    void socioToolsDenyWithoutSocioPermission() {
        // The regression that matters: an authenticated agent must not be able to read the whole
        // member registry just because it is allowed to read the catalog.
        assertThrows(AccessDeniedException.class, () -> socioMcpTools.listSocios());
        assertThrows(AccessDeniedException.class, () -> socioMcpTools.getSocio(10001L));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("no tool is reachable by an anonymous caller")
    void toolsDenyAnonymousCallers() {
        assertThrows(AccessDeniedException.class, () -> movieMcpTools.listMovies());
        assertThrows(AccessDeniedException.class, () -> socioMcpTools.listSocios());
    }

    @Test
    @DisplayName("no tool is reachable with an empty security context")
    void toolsDenyMissingSecurityContext() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> movieMcpTools.listMovies());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> socioMcpTools.listSocios());
    }

}
