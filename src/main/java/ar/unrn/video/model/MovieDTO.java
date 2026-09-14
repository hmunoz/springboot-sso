package ar.unrn.video.model;

import ar.unrn.video.domain.Genre;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class MovieDTO {

    private Long id;

    @NotNull
    @Size(max = 255)
    @MovieTitleUnique
    private String title;

    private Genre genre;

    @DecimalMin(value = "0.0", message = "Price must be zero or positive")
    private BigDecimal price;

    @Size(max = 1024)
    private String imageUrl;

}
