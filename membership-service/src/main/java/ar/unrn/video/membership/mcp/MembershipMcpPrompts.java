package ar.unrn.video.membership.mcp;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * MCP prompt exposing the member creation flow and, more importantly, the reason a freshly
 * created member does not show up immediately.
 *
 * <p>Every step below is verified against the code that implements it:
 *
 * <ul>
 *   <li>{@code UserResource#createUser} handles {@code POST /api/users} and delegates to
 *   {@code UserService#createUser}, which calls {@code KeycloakAdminClient#createUser} — the user
 *   is created in Keycloak, never written to the local {@code Socio} table directly;</li>
 *   <li>Keycloak's event SPI publishes that creation to the {@code keycloak.events} queue, which
 *   {@code KeycloakEventListener#onKeycloakEvent} consumes;</li>
 *   <li>{@code KeycloakEventListener} is the Anti-Corruption Layer: it normalizes the raw Keycloak
 *   event into a canonical {@code Event<String, SocioPayload>} and republishes it on the
 *   {@code videoclub.events} exchange (see {@code traducirEventoDeUsuario} /
 *   {@code traducirEventoDeAdmin} and {@code publicar});</li>
 *   <li>only then does {@code SocioEventListener#onSocioEvent} consume that domain event and call
 *   {@code SocioService#crearSocioDesdeEvento}, which is what actually inserts the {@code Socio}
 *   row.</li>
 * </ul>
 *
 * <p>That chain runs asynchronously after the {@code 201} response, so it is eventual
 * consistency, not a bug: {@code list_socios} called immediately after creation may legitimately
 * not show the new member yet.
 *
 * <h2>Why no {@code @PreAuthorize}</h2>
 *
 * <p>Same rationale as {@code MovieMcpPrompts} in catalog-service: this method returns a fixed
 * procedure, not member data, so there is nothing here for an authorization check to protect.
 * With no {@code @PreAuthorize}, this bean is never proxied, so the proxy-aware discovery bet
 * documented on {@code SocioMcpTools} does not apply to it — {@code SyncMcpAnnotationProviders}
 * discovers the plain class directly.
 */
@Component
public class MembershipMcpPrompts {

    @McpPrompt(
            name = "membership-alta-socio",
            title = "Alta de socio",
            description = "Flujo de alta y por que el socio no aparece de inmediato.")
    public GetPromptResult altaSocio() {
        final String texto = """
                El alta de un socio se hace con `POST /api/users`, que crea el usuario en \
                Keycloak, no en la base local del padron.

                Keycloak emite un evento de alta que el SPI publica en la cola \
                `keycloak.events`. `KeycloakEventListener`, la capa anticorrupcion del servicio, \
                lo traduce a un evento de dominio y lo republica en `videoclub.events`. Recien \
                ahi `SocioEventListener` consume ese evento y crea el `Socio` en la base local.

                Es consistencia eventual: entre el 201 de la creacion y el socio visible en \
                `list_socios` pasan milisegundos, pero pasan. Si `list_socios` se consulta \
                inmediatamente despues del alta y el socio todavia no aparece, eso no significa \
                que el alta haya fallado: conviene reintentar la consulta en unos segundos antes \
                de asumir un error.
                """;
        return new GetPromptResult(
                "Flujo de alta de socio",
                List.of(new PromptMessage(Role.USER, new TextContent(texto))));
    }

}
