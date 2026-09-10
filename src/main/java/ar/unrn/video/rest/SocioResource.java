package ar.unrn.video.rest;

import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.service.SocioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/socios", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Socios", description = "Videoclub members synchronized from Keycloak")
public class SocioResource {

    private final SocioService socioService;

    public SocioResource(final SocioService socioService) {
        this.socioService = socioService;
    }

    @GetMapping
    @Operation(summary = "List all members, most recently registered first", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAuthority('socio-permission-read')")
    public ResponseEntity<List<SocioDTO>> getAllSocios() {
        return ResponseEntity.ok(socioService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single member by local id", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAuthority('socio-permission-read')")
    public ResponseEntity<SocioDTO> getSocio(@PathVariable final Long id) {
        return ResponseEntity.ok(socioService.getById(id));
    }

    @PostMapping("/sync")
    @Operation(
            summary = "Reconcile members against Keycloak",
            description = "Compares the local replica with the identity provider and repairs gaps left by "
                    + "events that were never delivered. Also provisions users that predate this feature.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PreAuthorize("hasAuthority('user-permission-create')")
    public ResponseEntity<Map<String, Integer>> sync() {
        return ResponseEntity.ok(Map.of("sincronizados", socioService.sincronizarDesdeKeycloak()));
    }

}
