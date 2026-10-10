package ru.cineflow.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.cineflow.booking.service.AnalyticsService;
import ru.cineflow.booking.service.AssistantService;
import ru.cineflow.booking.service.BotSimulator;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Админка, ассистент и «Киногод»")
public class AdminController {
    private final AnalyticsService analytics;
    private final BotSimulator bots;
    private final AssistantService assistant;

    public AdminController(AnalyticsService analytics, BotSimulator bots, AssistantService assistant) {
        this.analytics = analytics; this.bots = bots; this.assistant = assistant;
    }

    public record BotsRequest(Long sessionId, int count) {}

    @Operation(summary = "Аналитика: выручка по дням, топ фильмов, загрузка залов, средний чек, доля бара")
    @GetMapping("/api/admin/analytics")
    public Map<String, Object> dashboard() { return analytics.dashboard(); }

    @Operation(summary = "Продажи сеанса по минутам")
    @GetMapping("/api/admin/analytics/session/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable Long id) { return analytics.timeline(id); }

    @Operation(summary = "Демо: история продаж за 2 недели (+ личная история пользователя)")
    @PostMapping("/api/admin/demo/seed")
    public Map<String, Object> seed(@RequestParam(required = false) Long userId) { return analytics.seedDemo(userId); }

    @Operation(summary = "Демо: заполнить зал для показа листа ожидания")
    @PostMapping("/api/admin/demo/fill")
    public Map<String, Object> fill(@RequestParam(required = false) Long sessionId) { return analytics.fillSession(sessionId); }

    @Operation(summary = "Демо: запустить N ботов-зрителей")
    @PostMapping("/api/admin/bots")
    public BotSimulator.BotStatus startBots(@RequestBody BotsRequest req) {
        return bots.start(req.sessionId(), req.count() <= 0 ? 100 : req.count());
    }

    @Operation(summary = "Статус ботов")
    @GetMapping("/api/admin/bots")
    public BotSimulator.BotStatus botsStatus() { return bots.status(); }

    @Operation(summary = "Киноассистент: запрос на русском → сеанс и места")
    @PostMapping("/api/assistant")
    public AssistantService.AssistantReply ask(@RequestBody AssistantService.AssistantRequest req) { return assistant.ask(req); }

    @Operation(summary = "«Твой киногод» — статистика зрителя за год")
    @GetMapping("/api/wrapped/{userId}")
    public Map<String, Object> wrapped(@PathVariable Long userId) { return analytics.wrapped(userId); }
}
