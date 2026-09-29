package ar.unrn.video.catalog.service;

import ar.unrn.video.catalog.domain.Movie;
import ar.unrn.video.catalog.event.MovieDomainEvent;
import ar.unrn.video.catalog.model.MovieDTO;
import ar.unrn.video.catalog.repos.MovieRepository;
import ar.unrn.video.catalog.util.NotFoundException;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class MovieService {

    private final MovieRepository movieRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public MovieService(final MovieRepository movieRepository,
            final ApplicationEventPublisher applicationEventPublisher) {
        this.movieRepository = movieRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public List<MovieDTO> findAll() {
        final List<Movie> movies = movieRepository.findAll(Sort.by("id"));
        return movies.stream()
                .map(movie -> mapToDTO(movie, new MovieDTO()))
                .toList();
    }

    public List<MovieDTO> search(final String query) {
        final List<Movie> movies = movieRepository.findByTitleContainingIgnoreCase(query, Sort.by("id"));
        return movies.stream()
                .map(movie -> mapToDTO(movie, new MovieDTO()))
                .toList();
    }

    public MovieDTO get(final Long id) {
        return movieRepository.findById(id)
                .map(movie -> mapToDTO(movie, new MovieDTO()))
                .orElseThrow(NotFoundException::new);
    }

    @Transactional
    public Long create(final MovieDTO movieDTO) {
        final Movie movie = new Movie();
        mapToEntity(movieDTO, movie);
        final Movie saved = movieRepository.save(movie);

        applicationEventPublisher.publishEvent(
                new MovieDomainEvent(saved.getId(), saved.getTitle(), saved.getPrice(), MovieDomainEvent.CREATED));

        return saved.getId();
    }

    @Transactional
    public void update(final Long id, final MovieDTO movieDTO) {
        final Movie movie = movieRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        mapToEntity(movieDTO, movie);
        final Movie saved = movieRepository.save(movie);

        applicationEventPublisher.publishEvent(
                new MovieDomainEvent(saved.getId(), saved.getTitle(), saved.getPrice(), MovieDomainEvent.UPDATED));
    }

    public void delete(final Long id) {
        movieRepository.deleteById(id);
    }

    private MovieDTO mapToDTO(final Movie movie, final MovieDTO movieDTO) {
        movieDTO.setId(movie.getId());
        movieDTO.setTitle(movie.getTitle());
        movieDTO.setGenre(movie.getGenre());
        movieDTO.setPrice(movie.getPrice());
        movieDTO.setImageUrl(movie.getImageUrl());
        return movieDTO;
    }

    private Movie mapToEntity(final MovieDTO movieDTO, final Movie movie) {
        movie.setTitle(movieDTO.getTitle());
        movie.setGenre(movieDTO.getGenre());
        movie.setPrice(movieDTO.getPrice());
        movie.setImageUrl(movieDTO.getImageUrl());
        return movie;
    }

    public boolean titleExists(final String title) {
        return movieRepository.existsByTitleIgnoreCase(title);
    }

}
