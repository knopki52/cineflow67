package ru.cineflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Gateway — единая точка входа: маршрутизирует /api/** в микросервисы через Eureka,
 * проксирует WebSocket, раздаёт сайт и сводный Swagger UI, защищает маршруты Circuit Breaker'ом.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
