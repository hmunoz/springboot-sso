package ar.unrn.video.repos;

import ar.unrn.video.domain.Movie;
import org.springframework.data.jpa.repository.JpaRepository;


public interface MovieRepository extends JpaRepository<Movie, Long> {

    boolean existsByTitleIgnoreCase(String title);

}
