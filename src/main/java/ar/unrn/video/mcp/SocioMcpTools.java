package ar.unrn.video.mcp;

import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.service.SocioService;
import ar.unrn.video.util.NotFoundException;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools over the member registry.
 *
 * <p>{@code SocioService} carries no access control of its own — the REST layer guards it through
 * {@code SocioResource}. These tools reach the service directly, so without the {@code @PreAuthorize}
 * checks below the whole member registry would be readable by any authenticated token.
 *
 * <p>See {@link MovieMcpTools} for why {@code @PreAuthorize} and {@code @McpTool} can share a method
 * on Spring AI 2.0.1, what that assumes, and how to unwind it if a future release breaks it.
 */
@Component
public class SocioMcpTools {

    private final SocioService socioService;

    public SocioMcpTools(final SocioService socioService) {
        this.socioService = socioService;
    }

    @McpTool(
            name = "list_socios",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "List members",
            description = "Lists the VideoClub members, including whether each one is currently active."
    )
    @PreAuthorize("hasAuthority('socio-permission-read')")
    public List<SocioDTO> listSocios() {
        return socioService.findAll();
    }

    @McpTool(
            name = "get_socio",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "Get member by id",
            description = "Returns a single member by its identifier. Fails when no member matches the id."
    )
    @PreAuthorize("hasAuthority('socio-permission-read')")
    public SocioDTO getSocio(
            @McpToolParam(description = "Identifier of the member to retrieve", required = true)
            final Long id) {
        try {
            return socioService.getById(id);
        } catch (NotFoundException ex) {
            // See MovieMcpTools#getMovie: over MCP the message is the whole error.
            throw new NotFoundException("No member found with id " + id);
        }
    }

}
