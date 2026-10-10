package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.movie.model.Movie;

public interface MovieRepository extends JpaRepository<Movie, Long> {}
