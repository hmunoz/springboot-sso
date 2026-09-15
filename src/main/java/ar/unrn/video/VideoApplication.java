package ar.unrn.video;

import ar.unrn.video.config.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;


@SpringBootApplication
@ImportRuntimeHints(NativeRuntimeHints.class)
public class VideoApplication {

    public static void main(final String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }

}
