package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.movie.model.Cinema;
import java.util.List;

public interface CinemaRepository extends JpaRepository<Cinema, Long> {
    List<Cinema> findByCityId(Long cityId);
}
