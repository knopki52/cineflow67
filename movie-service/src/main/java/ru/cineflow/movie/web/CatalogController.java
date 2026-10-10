package ru.cineflow.movie.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import ru.cineflow.movie.dto.MovieView;
import ru.cineflow.movie.dto.Recommendations;
import ru.cineflow.movie.dto.ReviewRequest;
import ru.cineflow.movie.dto.SessionInfo;
import ru.cineflow.movie.model.Cinema;
import ru.cineflow.movie.model.City;
import ru.cineflow.movie.model.Review;
import ru.cineflow.movie.service.CatalogService;
import ru.cineflow.movie.service.DataSeeder;
import ru.cineflow.movie.service.ReviewService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Каталог")
public class CatalogController {
    private final CatalogService catalog;
    private final ReviewService reviewService;
    private final DataSeeder seeder;

    public CatalogController(CatalogService catalog, ReviewService reviewService, DataSeeder seeder) {
        this.catalog = catalog; this.reviewService = reviewService; this.seeder = seeder;
    }

    @Operation(summary = "Список городов сети")
    @GetMapping("/cities")
    public List<City> cities() { return catalog.cities(); }

    @Operation(summary = "Кинотеатры (для карты), опционально по городу")
    @GetMapping("/cinemas")
    public List<Cinema> cinemas(@RequestParam(required = false) Long cityId) { return catalog.cinemas(cityId); }

    @Operation(summary = "Фильмы в прокате в городе")
    @GetMapping("/movies")
    public List<MovieView> movies(@RequestParam(required = false) Long cityId) { return catalog.moviesInCity(cityId); }

    @Operation(summary = "Рекомендации: по жанрам истории покупок или топ недели")
    @GetMapping("/movies/recommendations")
    public Recommendations recommendations(@RequestParam(required = false) Long userId,
                                           @RequestParam(required = false) Long cityId) {
        return catalog.recommendations(userId, cityId);
    }

    @Operation(summary = "Карточка фильма с рейтингом")
    @GetMapping("/movies/{id}")
    public MovieView movie(@PathVariable Long id) { return catalog.movie(id); }

    @Operation(summary = "Сеансы фильма в городе на дату")
    @GetMapping("/movies/{id}/sessions")
    public List<SessionInfo> movieSessions(@PathVariable Long id,
                                           @RequestParam(required = false) Long cityId,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return catalog.movieSessions(id, cityId, date);
    }

    @Operation(summary = "Отзывы о фильме")
    @GetMapping("/movies/{id}/reviews")
    public List<Review> reviews(@PathVariable Long id) { return reviewService.list(id); }

    @Operation(summary = "Оставить отзыв (только после посещения сеанса)")
    @PostMapping("/movies/{id}/reviews")
    public Review addReview(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return reviewService.add(id, req);
    }

    @Operation(summary = "Информация о сеансе (зал, фильм, кинотеатр)")
    @GetMapping("/sessions/{id}")
    public SessionInfo session(@PathVariable Long id) { return catalog.session(id); }

    @Operation(summary = "Поиск сеансов по городу/кинотеатру/фильму и времени")
    @GetMapping("/sessions")
    public List<SessionInfo> sessions(@RequestParam(required = false) Long cityId,
                                      @RequestParam(required = false) Long cinemaId,
                                      @RequestParam(required = false) Long movieId,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return catalog.search(cityId, cinemaId, movieId, from, to);
    }

    @Operation(summary = "Демо: создать сеанс, который начнётся через ~62 минуты (для показа напоминаний)")
    @PostMapping("/sessions/demo-soon")
    public SessionInfo demoSoon() { return catalog.session(seeder.createSoonSession().id); }
}
