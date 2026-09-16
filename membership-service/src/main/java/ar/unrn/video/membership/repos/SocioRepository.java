package ar.unrn.video.membership.repos;

import ar.unrn.video.membership.domain.Socio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface SocioRepository extends JpaRepository<Socio, Long> {

    Optional<Socio> findByKeycloakId(String keycloakId);

    boolean existsByKeycloakId(String keycloakId);

    List<Socio> findAllByOrderByFechaAltaDesc();

}
