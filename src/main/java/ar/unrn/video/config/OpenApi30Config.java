package ar.unrn.video.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "Videoclub", version = "v1"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(implicit = @OAuthFlow(authorizationUrl = "${springdoc.oauth2.authorization-url}") ))
public class OpenApi30Config {
}
