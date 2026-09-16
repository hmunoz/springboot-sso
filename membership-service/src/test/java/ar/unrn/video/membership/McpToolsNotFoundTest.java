package ar.unrn.video.membership;

import ar.unrn.video.membership.mcp.SocioMcpTools;
import ar.unrn.video.membership.service.SocioService;
import ar.unrn.video.membership.util.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Over MCP the exception message is the entire error the agent receives; a bare
 * {@link NotFoundException} would reach it as "null".
 *
 * <p>The tools are built directly here rather than taken from a context, so no proxy is involved
 * and the assertions are about the message alone, never about authorization.
 */
class McpToolsNotFoundTest {

    @Test
    @DisplayName("an unknown member id produces an actionable message")
    void unknownSocioId() {
        SocioService socioService = mock(SocioService.class);
        when(socioService.getById(99999L)).thenThrow(new NotFoundException());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> new SocioMcpTools(socioService).getSocio(99999L));

        assertEquals("No member found with id 99999", ex.getMessage());
    }

}
