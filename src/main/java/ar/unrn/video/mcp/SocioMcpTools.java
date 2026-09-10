package ar.unrn.video.mcp;

import ar.unrn.video.model.SocioDTO;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools over the member registry.
 *
 * <p>Like {@link MovieMcpTools}, this class must stay unproxied so the MCP tool provider can see
 * its methods; the authority checks live in {@link AuthorizedSocioQueries}.
 */
@Component
public class SocioMcpTools {

    private final AuthorizedSocioQueries socios;

    public SocioMcpTools(final AuthorizedSocioQueries socios) {
        this.socios = socios;
    }

    @McpTool(
            name = "list_socios",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "List members",
            description = "Lists the VideoClub members, including whether each one is currently active."
    )
    public List<SocioDTO> listSocios() {
        return socios.findAll();
    }

    @McpTool(
            name = "get_socio",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
            ),
            title = "Get member by id",
            description = "Returns a single member by its identifier. Fails when no member matches the id."
    )
    public SocioDTO getSocio(
            @McpToolParam(description = "Identifier of the member to retrieve", required = true)
            final Long id) {
        return socios.getById(id);
    }

}
