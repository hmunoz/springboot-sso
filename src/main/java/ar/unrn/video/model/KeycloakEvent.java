package ar.unrn.video.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Strongly-typed representation of events published by Keycloak to RabbitMQ.
 * Supports both user events (keycloak.user.*) and administrative events (keycloak.admin.*).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakEvent(
        // User event fields
        String type,
        String realmId,
        String clientId,
        String userId,
        String ipAddress,
        Long time,
        String error,
        Map<String, String> details,

        // Admin event fields
        String operationType,
        String resourceType,
        String resourcePath,
        @JsonAlias({"auth", "authDetails"})
        AuthDetails auth,
        Object representation
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthDetails(
            String realmId,
            String clientId,
            String userId,
            String ipAddress
    ) {}

    public boolean isUserEvent() {
        return type != null && !type.isBlank();
    }

    public boolean isAdminEvent() {
        return operationType != null && !operationType.isBlank();
    }

    public String extractTargetUserId() {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        if (resourcePath != null && resourcePath.startsWith("users/")) {
            return resourcePath.substring("users/".length());
        }
        return null;
    }

    public String getUsername() {
        if (details != null && details.containsKey("username")) {
            return details.get("username");
        }
        if (representation instanceof Map<?, ?> repMap && repMap.containsKey("username")) {
            return String.valueOf(repMap.get("username"));
        }
        if (representation instanceof String repStr) {
            return repStr;
        }
        return null;
    }
}
