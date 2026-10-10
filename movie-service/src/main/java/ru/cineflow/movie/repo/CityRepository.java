package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.movie.model.City;

public interface CityRepository extends JpaRepository<City, Long> {}
