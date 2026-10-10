package ru.cineflow.notification;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserClient {
    record UserView(Long id, String name, String email, String role, int points, String level, int percent,
                    Long telegramChatId) {}

    @GetMapping("/internal/users/{id}")
    UserView user(@PathVariable("id") Long id);
}
