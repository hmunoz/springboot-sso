package ar.unrn.video.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Local read-only replica of the catalog's Movie aggregate (Event-Carried
 * State Transfer). The id is assigned from the incoming event, never
 * generated here: it must match catalog's primary key so both sides refer to
 * the same movie without a cross-database FK (ADR-013, ADR-015).
 */
@Entity
@Getter
@Setter
public class MovieProjection {

    @Id
    @Column(nullable = false, updatable = false)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
