package ar.unrn.video.repos;

import ar.unrn.video.domain.Test;
import org.springframework.data.jpa.repository.JpaRepository;


public interface TestRepository extends JpaRepository<Test, Long> {

    boolean existsByNameIgnoreCase(String name);

}
