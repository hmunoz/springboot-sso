package ar.unrn.video.client.keycloak.dto;

public record KeycloakCredentialDTO(
        String type,
        String value,
        boolean temporary
) {
    public static KeycloakCredentialDTO password(String password, boolean temporary) {
        return new KeycloakCredentialDTO("password", password, temporary);
    }
}
