package ar.unrn.video.client.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class KeycloakClientConfiguration {

    @Bean
    public KeycloakAdminClient keycloakAdminClient(
            KeycloakAuthInterceptor authInterceptor,
            @Value("${keycloak.admin.url:http://localhost:9091}") String adminUrl) {

        RestClient restClient = RestClient.builder()
                .baseUrl(adminUrl)
                .requestInterceptor(authInterceptor)
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(KeycloakAdminClient.class);
    }
}
