package ru.cineflow.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Уведомления")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @Operation(summary = "Последние доставленные уведомления (для отладки)")
    @GetMapping("/api/notifications/recent")
    public List<NotificationService.Delivered> recent() { return service.recent(); }
}
