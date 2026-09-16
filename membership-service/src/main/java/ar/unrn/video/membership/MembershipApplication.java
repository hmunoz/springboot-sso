package ar.unrn.video.membership;

import ar.unrn.video.membership.config.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;


@SpringBootApplication
@ImportRuntimeHints(NativeRuntimeHints.class)
public class MembershipApplication {

    public static void main(final String[] args) {
        SpringApplication.run(MembershipApplication.class, args);
    }

}
