package ar.unrn.video.membership.mcp;

import ar.unrn.video.membership.model.SocioDTO;
import ar.unrn.video.membership.service.SocioService;
import ar.unrn.video.membership.util.NotFoundException;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * MCP resource exposing a member's card as Markdown, injected by the application as context
 * instead of being fetched by the model through a tool call. See {@code MovieMcpResources} in
 * catalog-service for the general rationale (context injected by the application vs. data the
 * model asks for) and for why that requires the same {@code @PreAuthorize} as the equivalent
 * REST endpoint.
 *
 * <h2>{@code activo} is a boolean, never a status enum</h2>
 *
 * <p>{@code Socio.activo} is a plain {@code Boolean}: the domain has exactly two states, active
 * and logically deactivated ({@code fechaBaja} set by {@code SocioService#darDeBajaSocioDesdeEvento}).
 * There is no "Suspendido", no "Moroso", no tier of any kind anywhere in this codebase. Rendering
 * anything other than "activo" / "dado de baja" here would not be a harmless UI choice: a model
 * that reads an invented status in this card will repeat it in later answers with full
 * confidence, and nothing downstream would ever contradict it. Once a category exists in text the
 * model has read, it is real to the model, whether or not it is real in the domain.
 *
 * <p>Putting {@code @PreAuthorize} on a {@code @McpResource} method proxies this bean and depends
 * on the same proxy-aware discovery path documented on {@code SocioMcpTools}; see that class for
 * the full mechanism and the risk if a future Spring AI release drops it.
 */
@Component
public class MembershipMcpResources {

    private final SocioService socioService;

    public MembershipMcpResources(final SocioService socioService) {
        this.socioService = socioService;
    }

    @McpResource(
            uri = "membership://socios/{id}",
            name = "socio_card",
            title = "Ficha de socio",
            description = "Ficha de un socio del padron en Markdown, lista para inyectar como "
                    + "contexto.",
            mimeType = "text/markdown")
    @PreAuthorize("hasAuthority('socio-permission-read')")
    public String socioCard(final String id) {
        final Long socioId = parseId(id);
        final SocioDTO socio;
        try {
            socio = socioService.getById(socioId);
        } catch (NotFoundException ex) {
            // See SocioMcpTools#getSocio: over MCP the exception message is the whole error.
            throw new NotFoundException("No member found with id " + id);
        }
        return render(socio);
    }

    private static Long parseId(final String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            // The {id} template variable binds as a String (see McpResource template binding), so
            // a non-numeric id never reaches SocioService at all; it has to be rejected here with
            // a message that names the offending value instead of surfacing a bare parse failure.
            throw new NotFoundException("Invalid member id: " + id);
        }
    }

    private static String render(final SocioDTO socio) {
        // Only real SocioDTO fields: nombre, apellido, username, email, activo, fechaAlta,
        // fechaBaja. activo renders exclusively as "activo" / "dado de baja" — see class javadoc.
        final StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(socio.nombre()).append(' ').append(socio.apellido()).append("\n\n");
        markdown.append("- **Usuario:** ").append(socio.username()).append('\n');
        markdown.append("- **Email:** ").append(socio.email()).append('\n');
        markdown.append("- **Estado:** ")
                .append(Boolean.TRUE.equals(socio.activo()) ? "activo" : "dado de baja")
                .append('\n');
        markdown.append("- **Fecha de alta:** ")
                .append(socio.fechaAlta() != null ? socio.fechaAlta().toString() : "sin especificar")
                .append('\n');
        if (socio.fechaBaja() != null) {
            markdown.append("- **Fecha de baja:** ").append(socio.fechaBaja()).append('\n');
        }
        return markdown.toString();
    }

}
