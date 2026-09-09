package ar.unrn.video.client.keycloak;

import ar.unrn.video.client.keycloak.dto.KeycloakTokenResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;

@Component
public class KeycloakAuthInterceptor implements ClientHttpRequestInterceptor {

    private final RestClient tokenRestClient;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant expiresAt = Instant.MIN;

    public KeycloakAuthInterceptor(
            @Value("${keycloak.admin.url:http://localhost:9091}") String url,
            @Value("${keycloak.admin.realm:videoclub}") String realm,
            @Value("${keycloak.admin.client-id:videoclub-backend}") String clientId,
            @Value("${keycloak.admin.client-secret:dstNSsANvqlaGfZCJa1mcYzP1EBAYP4N}") String clientSecret) {
        this.tokenRestClient = RestClient.create();
        this.tokenUrl = url + "/realms/" + realm + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(getAccessToken());
        return execution.execute(request, body);
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);

        KeycloakTokenResponseDTO response = tokenRestClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(KeycloakTokenResponseDTO.class);

        if (response != null && response.accessToken() != null) {
            this.cachedToken = response.accessToken();
            this.expiresAt = Instant.now().plusSeconds(Math.max(10, response.expiresIn() - 15));
            return this.cachedToken;
        }

        throw new IllegalStateException("Failed to obtain M2M service token from Keycloak");
    }
}
