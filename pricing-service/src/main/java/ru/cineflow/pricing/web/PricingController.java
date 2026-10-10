package ru.cineflow.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.pricing.dto.PricingDtos.*;
import ru.cineflow.pricing.model.PromoCode;
import ru.cineflow.pricing.service.PricingProperties;
import ru.cineflow.pricing.service.PricingService;
import ru.cineflow.pricing.service.PromoRepository;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
@Tag(name = "Цены и промокоды")
public class PricingController {
    private final PricingService pricing;
    private final PricingProperties props;
    private final PromoRepository promos;

    public PricingController(PricingService pricing, PricingProperties props, PromoRepository promos) {
        this.pricing = pricing; this.props = props; this.promos = promos;
    }

    @Operation(summary = "Рассчитать стоимость выбранных мест с учётом промокода")
    @PostMapping("/pricing/quote")
    public Quote quote(@RequestBody QuoteRequest req) { return pricing.quote(req); }

    @Operation(summary = "Цены на все места зала (для подсказок на схеме)")
    @PostMapping("/pricing/seat-map")
    public SeatMapPrices seatMap(@RequestBody SessionPricing s) { return pricing.seatMap(s); }

    @Operation(summary = "Текущие правила ценообразования (из Config Server)")
    @GetMapping("/pricing/rules")
    public PricingProperties rules() { return props; }

    @Operation(summary = "Все промокоды")
    @GetMapping("/promos")
    public List<PromoCode> list() { return promos.findAll(); }

    @Operation(summary = "Создать или изменить промокод")
    @PostMapping("/promos")
    public PromoCode save(@RequestBody PromoCode promo) {
        if (promo.code == null || promo.code.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите код");
        promo.code = promo.code.trim().toUpperCase(Locale.ROOT);
        if (promo.type == null) promo.type = "PERCENT";
        return promos.save(promo);
    }

    @Operation(summary = "Удалить промокод")
    @DeleteMapping("/promos/{code}")
    public void delete(@PathVariable String code) { promos.deleteById(code.toUpperCase(Locale.ROOT)); }

    @Operation(summary = "Отметить использование промокода (вызывается после оплаты)")
    @PostMapping("/promos/{code}/redeem")
    public void redeem(@PathVariable String code) { pricing.redeem(code); }
}
