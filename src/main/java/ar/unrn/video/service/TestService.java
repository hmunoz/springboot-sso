package ar.unrn.video.service;

import ar.unrn.video.domain.Test;
import ar.unrn.video.model.TestDTO;
import ar.unrn.video.repos.TestRepository;
import ar.unrn.video.util.NotFoundException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;


@Service
public class TestService {

    private final TestRepository testRepository;

    public TestService(final TestRepository testRepository) {
        this.testRepository = testRepository;
    }

    public List<TestDTO> findAll() {
        final List<Test> tests = testRepository.findAll(Sort.by("id"));
        return tests.stream()
                .map(test -> mapToDTO(test, new TestDTO()))
                .toList();
    }

    public TestDTO get(final Long id) {
        return testRepository.findById(id)
                .map(test -> mapToDTO(test, new TestDTO()))
                .orElseThrow(NotFoundException::new);
    }

    public Long create(final TestDTO testDTO) {
        final Test test = new Test();
        mapToEntity(testDTO, test);
        return testRepository.save(test).getId();
    }

    public void update(final Long id, final TestDTO testDTO) {
        final Test test = testRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        mapToEntity(testDTO, test);
        testRepository.save(test);
    }

    public void delete(final Long id) {
        testRepository.deleteById(id);
    }

    private TestDTO mapToDTO(final Test test, final TestDTO testDTO) {
        testDTO.setId(test.getId());
        testDTO.setName(test.getName());
        return testDTO;
    }

    private Test mapToEntity(final TestDTO testDTO, final Test test) {
        test.setName(testDTO.getName());
        return test;
    }

    public boolean nameExists(final String name) {
        return testRepository.existsByNameIgnoreCase(name);
    }

}
