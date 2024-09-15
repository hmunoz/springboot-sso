package ar.unrn.video.rest;

import ar.unrn.video.model.TestDTO;
import ar.unrn.video.service.TestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping(value = "/movies", produces = MediaType.APPLICATION_JSON_VALUE)
public class MovieResource {

    private final TestService testService;

    public MovieResource(final TestService testService) {
        this.testService = testService;
    }

    @GetMapping
    @Operation(summary = "get all Test", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<TestDTO>> getAllTests() {
        return ResponseEntity.ok(testService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "get  Test", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TestDTO> getTest(@PathVariable(name = "id") final Long id) {
        return ResponseEntity.ok(testService.get(id));
    }

    @PostMapping
    @ApiResponse(responseCode = "201")
    @Operation(summary = "create  Test", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Long> createTest(@RequestBody @Valid final TestDTO testDTO) {
        final Long createdId = testService.create(testDTO);
        return new ResponseEntity<>(createdId, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "update  Test", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Long> updateTest(@PathVariable(name = "id") final Long id,
            @RequestBody @Valid final TestDTO testDTO) {
        testService.update(id, testDTO);
        return ResponseEntity.ok(id);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(summary = "delete by id", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteTest(@PathVariable(name = "id") final Long id) {
        testService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
