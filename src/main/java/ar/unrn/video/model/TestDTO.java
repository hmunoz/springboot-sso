package ar.unrn.video.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class TestDTO {

    private Long id;

    @NotNull
    @Size(max = 255)
    @TestNameUnique
    private String name;

}
