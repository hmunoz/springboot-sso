package ar.unrn.video.membership;

import ar.unrn.video.membership.mcp.MembershipMcpResources;
import ar.unrn.video.membership.model.SocioDTO;
import ar.unrn.video.membership.service.SocioService;
import ar.unrn.video.membership.util.NotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@link McpToolsSecurityTest}, but for {@link MembershipMcpResources}: discovery through
 * the proxy-aware path the running server uses, authorization, content — including the inactive
 * wording for {@code activo} and the absence of any invented status — and the actionable-message
 * requirement for invalid and unknown ids.
 *
 * <p>A minimal context is used on purpose: method security is real, {@code SocioService} is
 * mocked, and no database, broker or Keycloak is needed.
 */
@SpringJUnitConfig(McpResourcesSecurityTest.TestConfig.class)
class McpResourcesSecurityTest {

    private static final SocioDTO ACTIVE_SOCIO =
            new SocioDTO(10001L, "kc-id", "socio@unrn.edu.ar", "socio", "Juan", "Perez", true, null, null);

    private static final SocioDTO INACTIVE_SOCIO =
            new SocioDTO(10002L, "kc-id-2", "baja@unrn.edu.ar", "baja", "Ana", "Gomez", false, null, null);

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        SocioService socioService() {
            SocioService socioService = mock(SocioService.class);
            when(socioService.getById(10001L)).thenReturn(ACTIVE_SOCIO);
            when(socioService.getById(10002L)).thenReturn(INACTIVE_SOCIO);
            when(socioService.getById(99999L)).thenThrow(new NotFoundException());
            return socioService;
        }

        @Bean
        MembershipMcpResources membershipMcpResources(SocioService socioService) {
            return new MembershipMcpResources(socioService);
        }

    }

    @Autowired
    private MembershipMcpResources membershipMcpResources;

    @Test
    @DisplayName("the template resource is discoverable through the path the running server uses")
    void resourceIsDiscoverable() {
        assertTrue(AopUtils.isAopProxy(membershipMcpResources), "the resource bean is expected to be proxied");

        List<String> templateUris = SyncMcpAnnotationProviders
                .statelessResourceTemplateSpecifications(List.of(membershipMcpResources))
                .stream()
                .map(spec -> spec.resourceTemplate().uriTemplate())
                .toList();
        assertEquals(List.of("membership://socios/{id}"), templateUris);

        assertTrue(SyncMcpAnnotationProviders
                .statelessResourceSpecifications(List.of(membershipMcpResources))
                .isEmpty(), "this resource has no fixed uri, only a template");
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("the card allows a caller holding socio-permission-read and renders the real data")
    void resourceAllowsSocioPermission() {
        String card = membershipMcpResources.socioCard("10001");
        assertTrue(card.contains("Juan"));
        assertTrue(card.contains("Perez"));
        assertTrue(card.contains("socio"));
        assertTrue(card.contains("socio@unrn.edu.ar"));
        assertTrue(card.contains("activo"));
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("an inactive member renders as 'dado de baja' and never invents a status")
    void inactiveSocioNeverInventsAStatus() {
        String card = membershipMcpResources.socioCard("10002");
        assertTrue(card.contains("dado de baja"));
        assertFalse(card.toLowerCase().contains("moroso"));
        assertFalse(card.toLowerCase().contains("suspendido"));
    }

    @Test
    @WithMockUser(authorities = "movie-permission-read")
    @DisplayName("the card denies a caller without socio-permission-read")
    void resourceDeniesWithoutSocioPermission() {
        // The authority used belongs to the other service on purpose, same as McpToolsSecurityTest.
        assertThrows(AccessDeniedException.class, () -> membershipMcpResources.socioCard("10001"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("the card is not reachable by an anonymous caller")
    void resourceDeniesAnonymousCallers() {
        assertThrows(AccessDeniedException.class, () -> membershipMcpResources.socioCard("10001"));
    }

    @Test
    @DisplayName("the card is not reachable with an empty security context")
    void resourceDeniesMissingSecurityContext() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> membershipMcpResources.socioCard("10001"));
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("an unknown member id produces an actionable message")
    void unknownSocioId() {
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> membershipMcpResources.socioCard("99999"));
        assertEquals("No member found with id 99999", ex.getMessage());
    }

    @Test
    @WithMockUser(authorities = "socio-permission-read")
    @DisplayName("a non-numeric member id produces an actionable message")
    void invalidSocioId() {
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> membershipMcpResources.socioCard("not-a-number"));
        assertTrue(ex.getMessage().contains("not-a-number"));
    }

}
