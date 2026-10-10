package ru.cineflow.bar;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bar")
@Tag(name = "Бар")
public class BarController {
    private final BarService bar;
    private final MenuRepository menu;
    private final BarOrderRepository orders;

    public BarController(BarService bar, MenuRepository menu, BarOrderRepository orders) {
        this.bar = bar; this.menu = menu; this.orders = orders;
    }

    @Operation(summary = "Меню бара")
    @GetMapping("/menu")
    public List<MenuItem> menu() { return menu.findAll(); }

    @Operation(summary = "Посчитать стоимость заказа")
    @PostMapping("/quote")
    public BarService.Quote quote(@RequestBody BarService.QuoteRequest req) { return bar.quote(req); }

    @Operation(summary = "Создать заказ к креслу (вызывает booking-service после оплаты)")
    @PostMapping("/orders")
    public BarOrder create(@RequestBody BarService.CreateOrder req) { return bar.create(req); }

    @Operation(summary = "Активные заказы для экрана бара")
    @GetMapping("/orders")
    public List<BarOrder> list(@RequestParam(defaultValue = "false") boolean all) {
        return all ? orders.findTop100ByOrderByCreatedAtDesc()
                : orders.findByStatusInOrderByStartTimeAsc(List.of("NEW", "COOKING", "DELIVERING"));
    }

    @Operation(summary = "Сменить статус: COOKING / DELIVERING / DELIVERED")
    @PatchMapping("/orders/{id}/status")
    public BarOrder status(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return bar.setStatus(id, body.getOrDefault("status", "COOKING"));
    }

    @Operation(summary = "Отменить заказы по брони (компенсация при возврате)")
    @PostMapping("/orders/by-booking/{bookingOrderId}/cancel")
    public List<BarOrder> cancel(@PathVariable Long bookingOrderId) { return bar.cancelByBooking(bookingOrderId); }
}
