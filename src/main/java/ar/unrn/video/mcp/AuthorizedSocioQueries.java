package ar.unrn.video.mcp;

import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.service.SocioService;
import ar.unrn.video.util.NotFoundException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Authorization boundary for the member MCP tools.
 *
 * <p>{@code SocioService} carries no access control of its own — the REST layer guards it through
 * {@code SocioResource}. The MCP tools reach the service directly, so without these checks the
 * whole member registry would be readable by any authenticated token.
 *
 * <p>See {@link AuthorizedMovieQueries} for why the checks live here instead of on the
 * {@code @McpTool} methods.
 */
@Component
public class AuthorizedSocioQueries {

    private final SocioService socioService;

    public AuthorizedSocioQueries(final SocioService socioService) {
        this.socioService = socioService;
    }

    @PreAuthorize("hasAuthority('socio-permission-read')")
    public List<SocioDTO> findAll() {
        return socioService.findAll();
    }

    @PreAuthorize("hasAuthority('socio-permission-read')")
    public SocioDTO getById(final Long id) {
        try {
            return socioService.getById(id);
        } catch (NotFoundException ex) {
            // See AuthorizedMovieQueries#get: over MCP the message is the whole error.
            throw new NotFoundException("No member found with id " + id);
        }
    }

}
