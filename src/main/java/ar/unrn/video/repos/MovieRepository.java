package ar.unrn.video.repos;

import ar.unrn.video.domain.Movie;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;


public interface MovieRepository extends JpaRepository<Movie, Long> {

    boolean existsByTitleIgnoreCase(String title);

    List<Movie> findByTitleContainingIgnoreCase(String title, Sort sort);

}
