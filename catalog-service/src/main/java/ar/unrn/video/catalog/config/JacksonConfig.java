package ar.unrn.video.catalog.config;

/*
 * Duplicated by design (see docs/arquitectura-dos-servicios.md, decisions D1/D7).
 * Keep in sync with membership-service.
 */

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;


@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .disable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.ACCEPT_FLOAT_AS_INT
                )
                .disable(
                        DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS
                );
    }

}
