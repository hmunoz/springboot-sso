package ar.unrn.video.catalog;

import ar.unrn.video.catalog.config.NativeRuntimeHints;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.model.MovieTitleUnique;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hints that only matter once the service is compiled to a GraalVM native image.
 *
 * <p>Every other test in this module runs on the JVM, where reflection needs no registration, so
 * none of them can fail for a missing hint. The native image is where a missing hint turns into a
 * runtime error — and by then the feedback costs a multi-minute native build. These assertions move
 * that feedback into the ordinary test run.
 */
class NativeRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    NativeRuntimeHintsTest() {
        new NativeRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    @DisplayName("the movie DTO can be bound reflectively")
    void movieDtoIsRegistered() {
        assertTrue(RuntimeHintsPredicates.reflection().onType(MovieDTO.class).test(hints),
                "MovieDTO is serialized by Jackson over REST and MCP; without the hint it binds to nothing");
    }

    @Test
    @DisplayName("the unique-title validator exposes its constructor")
    void validatorConstructorIsRegistered() {
        // The regression this pins: SpringConstraintValidatorFactory builds this validator through
        // createBean(Class), which reads getDeclaredConstructors() to find the two-argument
        // constructor. On a native image an unregistered constructor is invisible, Spring falls back
        // to a no-arg constructor that does not exist, and every POST/PUT of a movie returns 500
        // with "No default constructor found". The JVM never reproduces it.
        assertTrue(
                RuntimeHintsPredicates.reflection()
                        .onConstructor(MovieTitleUnique.MovieTitleUniqueValidator.class
                                .getDeclaredConstructors()[0])
                        .test(hints),
                "the validator's constructor must be reachable by reflection in a native image");
    }

    @Test
    @DisplayName("the servlet-request proxy the validator receives can be generated")
    void servletRequestProxyIsRegistered() {
        // Second half of the same failure. The constructor takes an HttpServletRequest, and what
        // Spring injects is a JDK proxy over that interface. A native image cannot generate a proxy
        // class it was not told about, so registering the constructor alone only moves the 500 one
        // frame down the stack.
        assertTrue(RuntimeHintsPredicates.proxies().forInterfaces(HttpServletRequest.class).test(hints),
                "the JDK proxy over HttpServletRequest must be registered for the native image");
    }

}
