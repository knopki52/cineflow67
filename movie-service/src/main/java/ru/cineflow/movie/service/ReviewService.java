package ru.cineflow.movie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.movie.client.BookingGateway;
import ru.cineflow.movie.dto.ReviewRequest;
import ru.cineflow.movie.model.Review;
import ru.cineflow.movie.repo.MovieRepository;
import ru.cineflow.movie.repo.ReviewRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private final ReviewRepository reviews;
    private final MovieRepository movies;
    private final BookingGateway booking;

    public ReviewService(ReviewRepository reviews, MovieRepository movies, BookingGateway booking) {
        this.reviews = reviews; this.movies = movies; this.booking = booking;
    }

    public List<Review> list(Long movieId) { return reviews.findByMovieIdOrderByCreatedAtDesc(movieId); }

    /** Отзыв можно оставить только после сеанса: спрашиваем booking-service, был ли проход по билету. */
    public Review add(Long movieId, ReviewRequest req) {
        if (!movies.existsById(movieId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Фильм не найден");
        if (!booking.attended(req.userId(), movieId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Отзыв можно оставить после сеанса: билет должен быть отсканирован на входе");
        }
        Review r = reviews.findByMovieIdAndUserId(movieId, req.userId()).orElseGet(Review::new);
        r.movieId = movieId;
        r.userId = req.userId();
        r.userName = req.userName() == null || req.userName().isBlank() ? "Зритель" : req.userName();
        r.rating = req.rating();
        r.text = req.text();
        r.createdAt = LocalDateTime.now();
        log.info("Новый отзыв на фильм {} от пользователя {}: {}★", movieId, req.userId(), req.rating());
        return reviews.save(r);
    }
}
