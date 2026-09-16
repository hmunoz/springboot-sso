package ar.unrn.video.membership.client.keycloak.dto;

public record KeycloakUserDTO(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled
) {}
