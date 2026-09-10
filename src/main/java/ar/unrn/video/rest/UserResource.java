package ar.unrn.video.rest;

import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import ar.unrn.video.model.UserCreateRequestDTO;
import ar.unrn.video.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserResource {

    private final UserService userService;

    public UserResource(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List all users from Keycloak via declarative HTTP Interface", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAuthority('user-permission-read')")
    public ResponseEntity<List<KeycloakUserDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.findAllUsers());
    }

    @PostMapping
    @ApiResponse(responseCode = "201")
    @Operation(summary = "Create a user in Keycloak via declarative HTTP Interface", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAuthority('user-permission-create')")
    public ResponseEntity<Map<String, String>> createUser(@RequestBody @Valid final UserCreateRequestDTO request) {
        String userId = userService.createUser(request);
        return new ResponseEntity<>(Map.of("id", userId, "username", request.username()), HttpStatus.CREATED);
    }
}
