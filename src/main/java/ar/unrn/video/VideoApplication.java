package ar.unrn.video;

import ar.unrn.video.config.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@SpringBootApplication
@ImportRuntimeHints(NativeRuntimeHints.class)
public class VideoApplication {

    public static void main(final String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }


    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("http://localhost:5173").allowedOrigins("*").allowedMethods("*").allowedHeaders("*");
            }
        };
    }

}
