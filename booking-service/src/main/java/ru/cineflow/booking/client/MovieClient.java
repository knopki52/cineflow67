package ru.cineflow.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "movie-service")
public interface MovieClient {
    @GetMapping("/api/sessions/{id}")
    Dtos.SessionInfo session(@PathVariable("id") Long id);

    @GetMapping("/api/sessions")
    List<Dtos.SessionInfo> sessions(@RequestParam(value = "cityId", required = false) Long cityId,
                                    @RequestParam("from") String from,
                                    @RequestParam("to") String to);
}
