package ru.cineflow.movie.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** Feign-клиент: имя сервиса резолвится через Eureka. */
@FeignClient(name = "booking-service", path = "/internal")
public interface BookingClient {

    @GetMapping("/users/{userId}/movies")
    List<Long> userMovieIds(@PathVariable("userId") Long userId);

    @GetMapping("/attended")
    boolean attended(@RequestParam("userId") Long userId, @RequestParam("movieId") Long movieId);

    @GetMapping("/stats/top-movies")
    List<Long> topMovies(@RequestParam("days") int days);
}
