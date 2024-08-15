package ar.unrn.video.rest;

import ar.unrn.video.model.TestDTO;
import ar.unrn.video.service.TestService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(value = "/api/tests", produces = MediaType.APPLICATION_JSON_VALUE)
public class TestResource {

    private final TestService testService;

    public TestResource(final TestService testService) {
        this.testService = testService;
    }

    @GetMapping
    public ResponseEntity<List<TestDTO>> getAllTests() {
        return ResponseEntity.ok(testService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TestDTO> getTest(@PathVariable(name = "id") final Long id) {
        return ResponseEntity.ok(testService.get(id));
    }

    @PostMapping
    @ApiResponse(responseCode = "201")
    public ResponseEntity<Long> createTest(@RequestBody @Valid final TestDTO testDTO) {
        final Long createdId = testService.create(testDTO);
        return new ResponseEntity<>(createdId, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Long> updateTest(@PathVariable(name = "id") final Long id,
            @RequestBody @Valid final TestDTO testDTO) {
        testService.update(id, testDTO);
        return ResponseEntity.ok(id);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<Void> deleteTest(@PathVariable(name = "id") final Long id) {
        testService.delete(id);
        return ResponseEntity.noContent().build();
    }

}