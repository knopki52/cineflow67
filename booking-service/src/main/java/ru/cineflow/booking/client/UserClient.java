package ru.cineflow.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service")
public interface UserClient {
    @GetMapping("/internal/users/{id}")
    Dtos.UserView user(@PathVariable("id") Long id);

    @PostMapping("/internal/users/{id}/points/spend")
    Dtos.UserView spend(@PathVariable("id") Long id, @RequestBody Dtos.PointsRequest req);

    @PostMapping("/internal/users/{id}/points/return")
    Dtos.UserView giveBack(@PathVariable("id") Long id, @RequestBody Dtos.PointsRequest req);
}
