package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.movie.model.Hall;
import java.util.List;

public interface HallRepository extends JpaRepository<Hall, Long> {
    List<Hall> findByCinemaId(Long cinemaId);
}
