package ar.unrn.video.membership.config;

/*
 * Duplicated by design (see docs/arquitectura-dos-servicios.md, decisions D1/D7).
 * Keep in sync with catalog-service.
 */

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;


@Configuration
@EntityScan("ar.unrn.video.membership.domain")
@EnableJpaRepositories("ar.unrn.video.membership.repos")
@EnableTransactionManagement
public class DomainConfig {
}
