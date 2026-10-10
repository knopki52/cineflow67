package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.cineflow.movie.model.MovieSession;
import java.time.LocalDateTime;
import java.util.List;

public interface SessionRepository extends JpaRepository<MovieSession, Long> {

    boolean existsByStartTimeBetween(LocalDateTime from, LocalDateTime to);

    @Query("""
           select s from MovieSession s
           where s.startTime >= :from and s.startTime < :to
             and (:cityId is null or s.cityId = :cityId)
             and (:cinemaId is null or s.cinemaId = :cinemaId)
             and (:movieId is null or s.movieId = :movieId)
           order by s.startTime
           """)
    List<MovieSession> search(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                              @Param("cityId") Long cityId, @Param("cinemaId") Long cinemaId,
                              @Param("movieId") Long movieId);
}
