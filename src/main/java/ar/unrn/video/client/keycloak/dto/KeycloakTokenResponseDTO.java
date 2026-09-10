package ar.unrn.video.client.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KeycloakTokenResponseDTO(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("token_type") String tokenType
) {}
