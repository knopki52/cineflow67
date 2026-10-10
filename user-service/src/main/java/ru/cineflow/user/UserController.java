package ru.cineflow.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Пользователи и бонусы")
public class UserController {
    private final UserService users;
    private final UserRepository repo;
    private final LoyaltyTxRepository txs;

    public UserController(UserService users, UserRepository repo, LoyaltyTxRepository txs) {
        this.users = users; this.repo = repo; this.txs = txs;
    }

    public record LoginRequest(String name, String email) {}
    public record LinkRequest(String code, Long chatId, String username) {}
    public record TelegramAuth(Long chatId, String name, String username) {}
    public record PointsRequest(Long orderId, int points) {}

    @Operation(summary = "Вход по имени и почте (admin@…, control@…, bar@… — служебные роли)")
    @PostMapping("/api/users/login")
    public UserView login(@RequestBody LoginRequest req) { return users.view(users.login(req.name(), req.email())); }

    @Operation(summary = "Профиль и бонусная программа")
    @GetMapping("/api/users/{id}")
    public UserView get(@PathVariable Long id) { return users.view(users.get(id)); }

    @Operation(summary = "История операций с баллами")
    @GetMapping("/api/users/{id}/points-history")
    public List<LoyaltyTx> history(@PathVariable Long id) { return txs.findTop20ByUserIdOrderByCreatedAtDesc(id); }

    @Operation(summary = "Получить ссылку для привязки Telegram")
    @PostMapping("/api/users/{id}/telegram/code")
    public Map<String, String> linkCode(@PathVariable Long id) { return users.createLinkCode(id); }

    @Operation(summary = "Привязать Telegram по коду (вызывает бот)")
    @PostMapping("/api/users/telegram/link")
    public UserView link(@RequestBody LinkRequest req) {
        return users.view(users.linkTelegram(req.code(), req.chatId(), req.username()));
    }

    @Operation(summary = "Вход из Telegram по chatId (вызывает бот)")
    @PostMapping("/api/users/telegram/auth")
    public UserView telegramAuth(@RequestBody TelegramAuth req) {
        return users.view(users.telegramAuth(req.chatId(), req.name(), req.username()));
    }

    @Operation(summary = "Найти пользователя по Telegram chatId")
    @GetMapping("/api/users/by-chat/{chatId}")
    public UserView byChat(@PathVariable Long chatId) {
        return users.view(repo.findByTelegramChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Чат не привязан")));
    }

    // ---------- внутренние вызовы других сервисов ----------
    @Operation(summary = "[internal] Профиль для других сервисов")
    @GetMapping("/internal/users/{id}")
    public UserView internal(@PathVariable Long id) { return users.view(users.get(id)); }

    @Operation(summary = "[internal] Списать баллы при оплате")
    @PostMapping("/internal/users/{id}/points/spend")
    public UserView spend(@PathVariable Long id, @RequestBody PointsRequest req) {
        return users.view(users.spendPoints(id, req.orderId(), req.points()));
    }

    @Operation(summary = "[internal] Вернуть баллы (компенсация саги)")
    @PostMapping("/internal/users/{id}/points/return")
    public UserView giveBack(@PathVariable Long id, @RequestBody PointsRequest req) {
        return users.view(users.returnPoints(id, req.orderId(), req.points()));
    }
}
