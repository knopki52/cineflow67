package ru.cineflow.movie.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.movie.model.Review;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByMovieIdOrderByCreatedAtDesc(Long movieId);
    Optional<Review> findByMovieIdAndUserId(Long movieId, Long userId);
}
