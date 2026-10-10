package ru.cineflow.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.cineflow.booking.service.AnalyticsService;

import java.util.List;

/** Внутренние эндпоинты для других сервисов (не проксируются через Gateway). */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal")
public class InternalController {
    private final AnalyticsService analytics;

    public InternalController(AnalyticsService analytics) { this.analytics = analytics; }

    @Operation(summary = "Фильмы, на которые пользователь покупал билеты")
    @GetMapping("/users/{userId}/movies")
    public List<Long> movies(@PathVariable Long userId) { return analytics.userMovieIds(userId); }

    @Operation(summary = "Был ли пользователь на фильме (билет отсканирован)")
    @GetMapping("/attended")
    public boolean attended(@RequestParam Long userId, @RequestParam Long movieId) { return analytics.attended(userId, movieId); }

    @Operation(summary = "Топ фильмов по продажам")
    @GetMapping("/stats/top-movies")
    public List<Long> top(@RequestParam(defaultValue = "7") int days) { return analytics.topMovies(days); }
}
