package ru.cineflow.user;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LoyaltyProperties.class)
@OpenAPIDefinition(
        info = @Info(title = "User Service", version = "1.0",
                description = "Зрители, роли, привязка Telegram и бонусная программа (баллы, уровни Silver/Gold)"),
        servers = @Server(url = "/"))
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
