package ru.cineflow.booking;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.cineflow.booking.config.BookingProperties;

@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@EnableConfigurationProperties(BookingProperties.class)
@OpenAPIDefinition(
        info = @Info(title = "Booking Service", version = "1.0",
                description = "Живая схема зала, удержание мест в Redis, сага оплаты, билеты с QR, совместный поход, "
                        + "лист ожидания, контроль на входе, аналитика, «Киногод» и киноассистент"),
        servers = @Server(url = "/"))
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
