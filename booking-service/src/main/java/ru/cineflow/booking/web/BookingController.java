package ru.cineflow.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.cineflow.booking.service.CheckoutService;
import ru.cineflow.booking.service.SeatService;
import ru.cineflow.booking.service.Views.*;

@RestController
@RequestMapping("/api/booking")
@Tag(name = "Бронирование")
public class BookingController {
    private final SeatService seats;
    private final CheckoutService checkout;

    public BookingController(SeatService seats, CheckoutService checkout) {
        this.seats = seats; this.checkout = checkout;
    }

    @Operation(summary = "Схема зала: состояние и цена каждого места")
    @GetMapping("/sessions/{id}/map")
    public SeatMapView map(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        return seats.map(id, userId);
    }

    @Operation(summary = "Заполненность сеанса")
    @GetMapping("/sessions/{id}/stats")
    public Stats stats(@PathVariable Long id) { return seats.stats(id); }

    @Operation(summary = "Удержать места на 5 минут (Redis SET NX + TTL)")
    @PostMapping("/hold")
    public HoldResult hold(@RequestBody HoldRequest req) { return seats.hold(req.sessionId(), req.userId(), req.seats()); }

    @Operation(summary = "Отпустить места (пустой список — все свои места на сеансе)")
    @PostMapping("/release")
    public void release(@RequestBody HoldRequest req) {
        if (req.seats() == null || req.seats().isEmpty()) seats.releaseAll(req.sessionId(), req.userId());
        else seats.release(req.sessionId(), req.userId(), req.seats());
    }

    @Operation(summary = "Предварительный расчёт: цены, промокод, баллы, бар")
    @PostMapping("/quote")
    public QuoteView quote(@RequestBody CheckoutRequest req) { return checkout.quote(req); }

    @Operation(summary = "Оплатить заказ (сага: баллы → оплата → билеты → бар → события)")
    @PostMapping("/checkout")
    public CheckoutResult checkout(@RequestBody CheckoutRequest req) { return checkout.checkout(req); }
}
