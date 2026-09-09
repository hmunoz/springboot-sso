package ar.unrn.video.service;

import ar.unrn.video.client.keycloak.KeycloakAdminClient;
import ar.unrn.video.client.keycloak.dto.KeycloakCredentialDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakUserCreateDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import ar.unrn.video.model.UserCreateRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

@Service
public class UserService {

    private final KeycloakAdminClient keycloakAdminClient;
    private final String realm;

    public UserService(KeycloakAdminClient keycloakAdminClient,
                       @Value("${keycloak.admin.realm:videoclub}") String realm) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.realm = realm;
    }

    public List<KeycloakUserDTO> findAllUsers() {
        return keycloakAdminClient.getUsers(realm);
    }

    public String createUser(UserCreateRequestDTO request) {
        List<KeycloakCredentialDTO> credentials = List.of(
                KeycloakCredentialDTO.password(request.password(), false)
        );

        String groupPath = (request.group() != null && !request.group().isBlank())
                ? request.group()
                : "/videoclub-default/cliente";

        KeycloakUserCreateDTO keycloakUser = new KeycloakUserCreateDTO(
                request.username(),
                request.email(),
                request.firstName(),
                request.lastName(),
                true,
                true,
                credentials,
                List.of(groupPath)
        );

        ResponseEntity<Void> response = keycloakAdminClient.createUser(realm, keycloakUser);

        URI location = response.getHeaders().getLocation();
        if (location != null) {
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return request.username();
    }
}
