package ar.unrn.video.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;


@Configuration
@EntityScan("ar.unrn.video.domain")
@EnableJpaRepositories("ar.unrn.video.repos")
@EnableTransactionManagement
public class DomainConfig {
}
