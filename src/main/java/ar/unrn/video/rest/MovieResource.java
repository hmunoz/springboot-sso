package ar.unrn.video.rest;

import ar.unrn.video.model.MovieDTO;
import ar.unrn.video.service.MovieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(value = "/movies", produces = MediaType.APPLICATION_JSON_VALUE)
public class MovieResource {

    private final MovieService movieService;

    public MovieResource(final MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    @Operation(summary = "Get all movies", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<MovieDTO>> getAllMovies() {
        return ResponseEntity.ok(movieService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get movie by id", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MovieDTO> getMovie(@PathVariable(name = "id") final Long id) {
        return ResponseEntity.ok(movieService.get(id));
    }

    @PostMapping
    @ApiResponse(responseCode = "201")
    @Operation(summary = "Create movie", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Long> createMovie(@RequestBody @Valid final MovieDTO movieDTO) {
        final Long createdId = movieService.create(movieDTO);
        return new ResponseEntity<>(createdId, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update movie", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Long> updateMovie(@PathVariable(name = "id") final Long id,
            @RequestBody @Valid final MovieDTO movieDTO) {
        movieService.update(id, movieDTO);
        return ResponseEntity.ok(id);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204")
    @Operation(summary = "Delete movie by id", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> deleteMovie(@PathVariable(name = "id") final Long id) {
        movieService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
