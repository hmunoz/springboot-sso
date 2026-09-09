package ar.unrn.video.client.keycloak.dto;

import java.util.List;

public record KeycloakUserCreateDTO(
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        List<KeycloakCredentialDTO> credentials,
        List<String> groups
) {}
