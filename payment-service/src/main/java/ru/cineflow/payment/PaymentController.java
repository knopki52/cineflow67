package ru.cineflow.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Оплата")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    /** OK — всё работает, FAIL — каждый платёж падает с 500, SLOW — платёж «висит» дольше таймаута. */
    public enum ChaosMode { OK, FAIL, SLOW }

    public record PaymentRequest(String orderRef, Long userId, int amount, String description) {}
    public record PaymentResult(String paymentId, String status, int amount, LocalDateTime paidAt) {}
    public record RefundRequest(String paymentId, int amount, String reason) {}

    private final AtomicReference<ChaosMode> mode = new AtomicReference<>(ChaosMode.OK);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    @Value("${payment.slow-delay-ms:6000}")
    private long slowDelay;

    @Operation(summary = "Провести оплату")
    @PostMapping
    public PaymentResult pay(@RequestBody PaymentRequest req) throws InterruptedException {
        ChaosMode m = mode.get();
        if (m == ChaosMode.FAIL) {
            failed.incrementAndGet();
            log.error("CHAOS: платёж {} на {} ₽ отклонён — шлюз «упал»", req.orderRef(), req.amount());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Платёжный шлюз недоступен");
        }
        if (m == ChaosMode.SLOW) {
            log.warn("CHAOS: платёж {} подвис на {} мс", req.orderRef(), slowDelay);
            Thread.sleep(slowDelay);
        }
        if (req.amount() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма не может быть отрицательной");
        processed.incrementAndGet();
        String id = "pay_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Платёж {} на {} ₽ по заказу {} проведён", id, req.amount(), req.orderRef());
        return new PaymentResult(id, "SUCCESS", req.amount(), LocalDateTime.now());
    }

    @Operation(summary = "Вернуть деньги")
    @PostMapping("/refund")
    public PaymentResult refund(@RequestBody RefundRequest req) {
        if (mode.get() == ChaosMode.FAIL) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Платёжный шлюз недоступен");
        }
        log.info("Возврат {} ₽ по платежу {} ({})", req.amount(), req.paymentId(), req.reason());
        return new PaymentResult("ref_" + UUID.randomUUID().toString().substring(0, 8), "REFUNDED",
                req.amount(), LocalDateTime.now());
    }

    @Operation(summary = "Текущий режим хаоса и статистика")
    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        return Map.of("mode", mode.get(), "processed", processed.get(), "failed", failed.get());
    }

    @Operation(summary = "Включить режим хаоса: OK / FAIL / SLOW")
    @PostMapping("/chaos")
    public Map<String, Object> setChaos(@RequestBody Map<String, String> body) {
        ChaosMode m = ChaosMode.valueOf(body.getOrDefault("mode", "OK").toUpperCase());
        mode.set(m);
        log.warn("Режим хаоса переключён: {}", m);
        return chaos();
    }
}
