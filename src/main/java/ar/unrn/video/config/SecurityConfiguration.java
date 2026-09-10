package ar.unrn.video.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    /**
     * Public identifier of the MCP resource, advertised in the OAuth 2.0 Protected Resource
     * Metadata document (RFC 9728) so MCP clients can discover Keycloak on their own.
     */
    @Value("${videoclub.mcp.resource-uri:http://localhost:8080/mcp}")
    private String mcpResourceUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable);

        http.sessionManagement(sessionManagement ->
                sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );


        org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver bearerTokenResolver =
                new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        bearerTokenResolver.setAllowUriQueryParameter(true);

        http
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/**","/metrics/**", "/swagger-ui/**", "/api-docs/**").permitAll()
                        // RFC 9728 discovery document: an MCP client reads it before it has a token.
                        // /mcp itself stays protected by the anyRequest() rule below.
                        .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2Configurer -> oauth2Configurer
                        .bearerTokenResolver(bearerTokenResolver)
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(mcpResourceUri)
                                        .resourceName("VideoClub MCP Server")
                                        .authorizationServer(issuerUri)
                                        // The builder already defaults to "header"; replacing the
                                        // list instead of appending keeps it from being emitted twice.
                                        .bearerMethods(methods -> {
                                            methods.clear();
                                            methods.add("header");
                                        })
                                        // No mTLS in this stack: advertising bound tokens would tell
                                        // clients to expect a certificate that is never requested.
                                        .tlsClientCertificateBoundAccessTokens(false)))
                        .jwt(jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults(""); // Remove the ROLE_ prefix
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakGrantedAuthoritiesConverter());
        return converter;
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOrigins(java.util.List.of("http://localhost:5173", "http://localhost:3000"));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

}