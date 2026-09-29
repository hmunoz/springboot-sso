package ar.unrn.video.membership.repos;

import ar.unrn.video.membership.domain.MovieProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieProjectionRepository extends JpaRepository<MovieProjection, Long> {
}
