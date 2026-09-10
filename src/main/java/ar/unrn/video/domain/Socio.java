package ar.unrn.video.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;


/**
 * Local business replica of a Keycloak user.
 *
 * <p>Keycloak stays the source of truth for identity; this entity is the videoclub's own
 * projection of it, so business records (rentals, reservations) can reference a member
 * without reaching out to the identity provider on every query.
 */
@Entity
@Getter
@Setter
public class Socio {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
            name = "primary_sequence",
            sequenceName = "primary_sequence",
            allocationSize = 1,
            initialValue = 10000
    )
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "primary_sequence"
    )
    private Long id;

    /**
     * Keycloak subject id. The unique constraint is the last line of defence for
     * idempotency: if two consumers process the same CREATE event concurrently, the
     * database rejects the duplicate instead of silently storing two members.
     */
    @Column(nullable = false, unique = true, updatable = false)
    private String keycloakId;

    /**
     * Nullable on purpose: Keycloak allows users without an email address, and a NOT NULL
     * here would turn missing data into a constraint violation inside the RabbitMQ
     * consumer. Business validation belongs in the service layer.
     */
    @Column
    private String email;

    @Column
    private String username;

    @Column
    private String nombre;

    @Column
    private String apellido;

    @Column(nullable = false)
    private Boolean activo = Boolean.TRUE;

    @Column(nullable = false)
    private LocalDateTime fechaAlta;

    @Column
    private LocalDateTime fechaBaja;

}
