package ru.cineflow.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.cineflow.booking.service.GroupService;
import ru.cineflow.booking.service.TicketService;
import ru.cineflow.booking.service.Views.*;
import ru.cineflow.booking.service.WaitlistService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Билеты, совместный поход, лист ожидания, контроль")
public class TicketController {
    private final TicketService tickets;
    private final GroupService groups;
    private final WaitlistService waitlist;

    public TicketController(TicketService tickets, GroupService groups, WaitlistService waitlist) {
        this.tickets = tickets; this.groups = groups; this.waitlist = waitlist;
    }

    public record RefundRequest(Long userId) {}
    public record ScanRequest(String code, Long sessionId) {}
    public record WaitlistRequest(Long sessionId, Long userId, String userName) {}

    @Operation(summary = "Билеты пользователя")
    @GetMapping("/api/tickets")
    public List<TicketView> list(@RequestParam Long userId) { return tickets.byUser(userId); }

    @Operation(summary = "Билет")
    @GetMapping("/api/tickets/{id}")
    public TicketView get(@PathVariable String id) { return tickets.view(tickets.get(id)); }

    @Operation(summary = "QR-код билета (PNG)")
    @GetMapping(value = "/api/tickets/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String id, @RequestParam(defaultValue = "320") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .contentType(MediaType.IMAGE_PNG).body(tickets.qr(id, Math.min(Math.max(size, 120), 1024)));
    }

    @Operation(summary = "Вернуть билет (100% за сутки, 50% за 2–24 часа)")
    @PostMapping("/api/tickets/{id}/refund")
    public TicketView refund(@PathVariable String id, @RequestBody RefundRequest req) { return tickets.refund(id, req.userId()); }

    @Operation(summary = "Совместный поход: создать и получить ссылку")
    @PostMapping("/api/groups")
    public GroupService.GroupView createGroup(@RequestBody GroupService.CreateGroup req) { return groups.create(req); }

    @Operation(summary = "Совместный поход: статус «оплатили 3 из 4»")
    @GetMapping("/api/groups/{id}")
    public GroupService.GroupView group(@PathVariable String id) { return groups.view(id); }

    @Operation(summary = "Встать в лист ожидания")
    @PostMapping("/api/waitlist")
    public WaitlistService.Position join(@RequestBody WaitlistRequest req) {
        return waitlist.join(req.sessionId(), req.userId(), req.userName());
    }

    @Operation(summary = "Моё место в листе ожидания")
    @GetMapping("/api/waitlist")
    public WaitlistService.Position status(@RequestParam Long sessionId, @RequestParam Long userId) {
        return waitlist.status(sessionId, userId);
    }

    @Operation(summary = "Выйти из листа ожидания")
    @DeleteMapping("/api/waitlist")
    public void leave(@RequestParam Long sessionId, @RequestParam Long userId) { waitlist.leave(sessionId, userId); }

    @Operation(summary = "Контроль на входе: сканирование QR")
    @PostMapping("/api/control/scan")
    public ScanResult scan(@RequestBody ScanRequest req) { return tickets.scan(req.code(), req.sessionId()); }

    @Operation(summary = "Правила возврата")
    @GetMapping("/api/tickets/refund-rules")
    public Map<String, String> rules() {
        return Map.of("more24h", "100%", "from2to24h", "50%", "less2h", "возврат невозможен");
    }
}
