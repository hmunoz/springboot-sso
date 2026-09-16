package ar.unrn.video.catalog.config;

import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.model.MovieTitleUnique;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints required by GraalVM Native Image for catalog-service.
 *
 * <p>Duplicated by design (see docs/arquitectura-dos-servicios.md, decisions D1/D7): this class is
 * split by content, not copied. The Keycloak proxy and event hints live in the
 * membership-service copy, because catalog-service has none of those types on its classpath.
 */
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        // 1. Reflection for DTO records bound by Jackson / REST / MCP
        bindingRegistrar.registerReflectionHints(hints.reflection(), MovieDTO.class);

        // 2. Constructor of the custom Bean Validation validator.
        //
        // It is never a Spring bean: SpringConstraintValidatorFactory instantiates it on demand,
        // through AutowireCapableBeanFactory.createBean(Class). To pick its two-argument
        // constructor, Spring first asks the class for getDeclaredConstructors() — and on a native
        // image that returns nothing unless the constructor is registered here. With nothing to
        // choose from, Spring falls back to the no-arg constructor, which does not exist, and every
        // POST/PUT of a movie dies as a 500 with "No default constructor found".
        //
        // It fails only in native: on the JVM the same code works, so a green `./mvnw test` and a
        // healthy JVM container both say nothing about this.
        hints.reflection().registerType(MovieTitleUnique.MovieTitleUniqueValidator.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

        // 3. JDK proxy for the HttpServletRequest that validator's constructor receives.
        //
        // Spring does not hand over the live request — it is request-scoped and the validator is
        // not — but a proxy implementing HttpServletRequest that resolves the current request on
        // each call. A JDK proxy class is generated at runtime, which a native image cannot do
        // unless the exact interface list is known ahead of time. Without this, registering the
        // constructor above is not enough: creation gets one step further and then dies with
        // MissingReflectionRegistrationError on the proxy instead.
        hints.proxies().registerJdkProxy(HttpServletRequest.class);
    }
}
