package ar.unrn.video.catalog.config;

/*
 * Duplicated by design (see docs/arquitectura-dos-servicios.md, decisions D1/D7).
 * Keep in sync with membership-service.
 */

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;


@Configuration
@EntityScan("ar.unrn.video.catalog.domain")
@EnableJpaRepositories("ar.unrn.video.catalog.repos")
@EnableTransactionManagement
public class DomainConfig {
}
