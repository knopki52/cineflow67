package ru.cineflow.movie.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Обёртка над Feign-клиентом с Circuit Breaker.
 * Если booking-service недоступен — рекомендации деградируют до «топа по рейтингу», а не падают.
 */
@Component
public class BookingGateway {
    private static final Logger log = LoggerFactory.getLogger(BookingGateway.class);
    private final BookingClient client;

    public BookingGateway(BookingClient client) { this.client = client; }

    @CircuitBreaker(name = "booking", fallbackMethod = "historyFallback")
    public List<Long> userMovieIds(Long userId) { return client.userMovieIds(userId); }

    public List<Long> historyFallback(Long userId, Throwable t) {
        log.warn("booking-service недоступен, рекомендации без истории: {}", t.toString());
        return List.of();
    }

    @CircuitBreaker(name = "booking", fallbackMethod = "topFallback")
    public List<Long> topMovies(int days) { return client.topMovies(days); }

    public List<Long> topFallback(int days, Throwable t) { return List.of(); }

    @CircuitBreaker(name = "booking", fallbackMethod = "attendedFallback")
    public boolean attended(Long userId, Long movieId) { return client.attended(userId, movieId); }

    public boolean attendedFallback(Long userId, Long movieId, Throwable t) {
        log.warn("Не удалось проверить посещение: {}", t.toString());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Сервис бронирования недоступен, не можем проверить билет. Попробуйте через минуту.");
    }
}
