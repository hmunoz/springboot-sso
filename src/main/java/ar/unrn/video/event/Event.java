package ar.unrn.video.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Canonical domain event envelope published to the business exchange.
 *
 * <p>This type is deliberately free of any Keycloak concept: the Anti-Corruption Layer
 * translates provider-specific events into this shape, so the consuming bounded context
 * never depends on the identity provider's wire format.
 *
 * @param aggregate name of the aggregate the event belongs to (e.g. {@code Socio})
 * @param type      what happened to the aggregate
 * @param key       business key of the aggregate instance
 * @param data      state carried by the event (Event-Carried State Transfer)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Event<K, T>(
        String aggregate,
        Type type,
        K key,
        T data
) {

    public enum Type {
        CREATE, UPDATE, DELETE
    }

    public static <K, T> Event<K, T> of(String aggregate, Type type, K key, T data) {
        return new Event<>(aggregate, type, key, data);
    }

    /**
     * Topic routing key, e.g. {@code Socio.CREATE}. Derived, never serialized: the
     * consumer must not depend on a field that only makes sense at publish time.
     */
    @JsonIgnore
    public String routingKey() {
        return aggregate + "." + type.name();
    }

}
