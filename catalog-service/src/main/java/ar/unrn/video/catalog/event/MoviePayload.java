package ar.unrn.video.catalog.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Canonical Movie projection carried on the wire (Event-Carried State Transfer).
 *
 * <p>Membership never calls back into catalog to resolve a price: this payload
 * carries everything the local {@code movie_projection} replica needs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MoviePayload(
        Long movieId,
        String title,
        BigDecimal price
) {
}
