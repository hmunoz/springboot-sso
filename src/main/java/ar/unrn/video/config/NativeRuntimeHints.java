package ar.unrn.video.config;

import ar.unrn.video.client.keycloak.KeycloakAdminClient;
import ar.unrn.video.client.keycloak.dto.KeycloakCredentialDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakTokenResponseDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakUserCreateDTO;
import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import ar.unrn.video.event.Event;
import ar.unrn.video.event.SocioPayload;
import ar.unrn.video.model.KeycloakEvent;
import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.model.UserCreateRequestDTO;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection and proxy hints required by GraalVM Native Image for springboot-sso.
 */
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        // 1. Dynamic Proxy for Spring HTTP Interface KeycloakAdminClient
        hints.proxies().registerJdkProxy(KeycloakAdminClient.class);
        hints.proxies().registerJdkProxy(
                org.springframework.aop.SpringProxy.class,
                org.springframework.aop.framework.Advised.class,
                org.springframework.core.DecoratingProxy.class,
                KeycloakAdminClient.class
        );

        // 2. Reflection on KeycloakAdminClient methods and annotations
        hints.reflection().registerType(KeycloakAdminClient.class,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INVOKE_PUBLIC_METHODS);

        // 3. Reflection for DTO records bound by Jackson / REST / AMQP
        bindingRegistrar.registerReflectionHints(hints.reflection(),
                KeycloakTokenResponseDTO.class,
                KeycloakUserDTO.class,
                KeycloakUserCreateDTO.class,
                KeycloakCredentialDTO.class,
                UserCreateRequestDTO.class,
                KeycloakEvent.class,
                KeycloakEvent.AuthDetails.class,
                SocioPayload.class,
                Event.class,
                SocioDTO.class,
                MovieDTO.class);
    }
}
