package ar.unrn.video.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Business contract of a Socio domain event.
 *
 * <p>Carries the full state instead of only the identifier (Event-Carried State
 * Transfer), so the consumer never has to call Keycloak back to process an event.
 * On DELETE only {@code keycloakId} is meaningful.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SocioPayload(
        String keycloakId,
        String email,
        String username,
        String nombre,
        String apellido
) {

    public static SocioPayload ofKey(String keycloakId) {
        return new SocioPayload(keycloakId, null, null, null, null);
    }

}
