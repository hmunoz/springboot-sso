package ar.unrn.video.membership;

import ar.unrn.video.membership.mcp.MembershipMcpPrompts;
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
 * {@link MembershipMcpPrompts} carries no {@code @PreAuthorize}, so it is built directly here,
 * exactly like {@link McpToolsNotFoundTest} builds {@code SocioMcpTools} directly: no proxy is
 * involved and no Spring context is needed.
 */
class McpPromptsTest {

    private final MembershipMcpPrompts prompts = new MembershipMcpPrompts();

    @Test
    @DisplayName("the prompt is discoverable through the path the running server uses, and the bean is never proxied")
    void promptIsDiscoverable() {
        // Without @PreAuthorize, Spring never wraps this bean in a proxy in the first place —
        // unlike MembershipMcpResources, whose discovery depends on resolving the proxy's target class.
        assertFalse(AopUtils.isAopProxy(prompts), "a prompt bean carries no @PreAuthorize and is never proxied");

        List<String> promptNames = SyncMcpAnnotationProviders
                .statelessPromptSpecifications(List.of(prompts))
                .stream()
                .map(spec -> spec.prompt().name())
                .toList();
        assertEquals(List.of("membership-alta-socio"), promptNames);
    }

    @Test
    @DisplayName("the procedure explains the Keycloak-first flow and the eventual consistency it implies")
    void encodesTheRealFlow() {
        String text = textOf(prompts.altaSocio());

        // The alta happens in Keycloak, not the local database.
        assertTrue(text.contains("POST /api/users"));
        assertTrue(text.contains("Keycloak"));

        // The anti-corruption layer and the two queues it bridges.
        assertTrue(text.contains("keycloak.events"));
        assertTrue(text.contains("KeycloakEventListener"));
        assertTrue(text.contains("videoclub.events"));
        assertTrue(text.contains("SocioEventListener"));

        // The consequence: a 201 followed immediately by list_socios may not show the member yet,
        // and the procedure must say plainly that this is not a failure.
        assertTrue(text.contains("list_socios"));
        assertTrue(text.toLowerCase().contains("consistencia eventual"));
        assertTrue(text.toLowerCase().contains("no significa que el alta haya fallado"));
    }

    private static String textOf(GetPromptResult result) {
        PromptMessage message = result.messages().get(0);
        return ((TextContent) message.content()).text();
    }

}
