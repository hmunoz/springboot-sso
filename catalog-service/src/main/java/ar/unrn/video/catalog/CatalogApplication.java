package ar.unrn.video.catalog;

import ar.unrn.video.catalog.config.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;


@SpringBootApplication
@ImportRuntimeHints(NativeRuntimeHints.class)
public class CatalogApplication {

    public static void main(final String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

}
