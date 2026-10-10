package ru.cineflow.notification;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserLookup {
    private static final Logger log = LoggerFactory.getLogger(UserLookup.class);
    private final UserClient client;

    public UserLookup(UserClient client) { this.client = client; }

    @CircuitBreaker(name = "user", fallbackMethod = "fallback")
    public UserClient.UserView find(Long id) { return client.user(id); }

    public UserClient.UserView fallback(Long id, Throwable t) {
        log.debug("Пользователь {} не найден или user-service недоступен: {}", id, t.toString());
        return null;
    }
}
