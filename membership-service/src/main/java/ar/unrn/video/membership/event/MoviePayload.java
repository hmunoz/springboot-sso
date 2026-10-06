package ar.unrn.video.membership.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Duplicated by design from catalog-service (see docs/arquitectura-dos-servicios.md,
 * decisions D1/D7): each bounded context owns its own copy of the wire contract it
 * consumes, so it never needs a shared library to stay decoupled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MoviePayload(
        Long movieId,
        String title,
        BigDecimal price
) {
}
