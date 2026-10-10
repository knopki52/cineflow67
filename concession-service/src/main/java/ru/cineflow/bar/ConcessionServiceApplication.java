package ru.cineflow.bar;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(title = "Concession Service", version = "1.0",
                description = "Бар кинотеатра: меню, заказы с доставкой к креслу и их статусы"),
        servers = @Server(url = "/"))
public class ConcessionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConcessionServiceApplication.class, args);
    }
}
