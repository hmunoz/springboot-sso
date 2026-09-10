package ar.unrn.video.client.keycloak;

import ar.unrn.video.client.keycloak.dto.KeycloakUserCreateDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

@HttpExchange("/admin/realms/{realm}")
public interface KeycloakAdminClient {

    @GetExchange("/users")
    List<KeycloakUserDTO> getUsers(@PathVariable("realm") String realm);

    @GetExchange("/users/{id}")
    KeycloakUserDTO getUserById(@PathVariable("realm") String realm, @PathVariable("id") String id);

    @PostExchange("/users")
    ResponseEntity<Void> createUser(@PathVariable("realm") String realm, @RequestBody KeycloakUserCreateDTO user);
}
