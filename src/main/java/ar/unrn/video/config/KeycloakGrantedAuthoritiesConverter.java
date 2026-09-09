package ar.unrn.video.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KeycloakGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt source) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Realm roles (e.g. ROLE_ADMIN, ROLE_CLIENT)
        Map<String, Object> realmAccess = source.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            roles.stream()
                    .filter(String.class::isInstance)
                    .map(r -> new SimpleGrantedAuthority((String) r))
                    .forEach(authorities::add);
        }

        // Client roles / fine-grained permissions (e.g. movie-permission-create, movie-permission-read)
        Map<String, Object> resourceAccess = source.getClaimAsMap("resource_access");
        if (resourceAccess != null) {
            for (Object clientObj : resourceAccess.values()) {
                if (clientObj instanceof Map<?, ?> clientMap && clientMap.get("roles") instanceof List<?> clientRoles) {
                    clientRoles.stream()
                            .filter(String.class::isInstance)
                            .map(r -> new SimpleGrantedAuthority((String) r))
                            .forEach(authorities::add);
                }
            }
        }

        return authorities;
    }
}
