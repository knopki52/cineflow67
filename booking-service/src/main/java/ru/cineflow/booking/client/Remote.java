package ru.cineflow.booking.client;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Все вызовы других микросервисов в одном месте и каждый — под защитой Circuit Breaker (Resilience4j).
 * Fallback-методы описывают, как сервис деградирует, если сосед упал.
 */
@Component
public class Remote {
    private static final Logger log = LoggerFactory.getLogger(Remote.class);

    private final MovieClient movies;
    private final PricingClient pricing;
    private final PaymentClient payments;
    private final ConcessionClient bar;
    private final UserClient users;
    /** Сеансы не меняются — кешируем, чтобы схема зала работала даже при падении movie-service. */
    private final Map<Long, SessionInfo> sessionCache = new ConcurrentHashMap<>();
    /** ссылка на прокси самого себя: иначе @CircuitBreaker не сработает при внутреннем вызове */
    @Autowired @Lazy
    private Remote self;

    public Remote(MovieClient movies, PricingClient pricing, PaymentClient payments,
                  ConcessionClient bar, UserClient users) {
        this.movies = movies; this.pricing = pricing; this.payments = payments; this.bar = bar; this.users = users;
    }

    // ---------------- movie-service ----------------
    public SessionInfo session(Long id) {
        SessionInfo cached = sessionCache.get(id);
        if (cached != null) return cached;
        SessionInfo s = self.loadSession(id);
        sessionCache.put(id, s);
        return s;
    }

    @CircuitBreaker(name = "movie", fallbackMethod = "sessionFallback")
    public SessionInfo loadSession(Long id) { return movies.session(id); }

    public SessionInfo sessionFallback(Long id, Throwable t) {
        if (t instanceof FeignException.NotFound) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Сеанс не найден");
        log.warn("movie-service недоступен: {}", t.toString());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Каталог временно недоступен");
    }

    @CircuitBreaker(name = "movie", fallbackMethod = "sessionsFallback")
    public List<SessionInfo> sessions(Long cityId, LocalDateTime from, LocalDateTime to) {
        List<SessionInfo> list = movies.sessions(cityId, from.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        list.forEach(s -> sessionCache.putIfAbsent(s.id(), s));
        return list;
    }

    public List<SessionInfo> sessionsFallback(Long cityId, LocalDateTime from, LocalDateTime to, Throwable t) {
        log.warn("movie-service недоступен, ищу по кешу: {}", t.toString());
        List<SessionInfo> out = new ArrayList<>();
        for (SessionInfo s : sessionCache.values()) {
            if ((cityId == null || cityId.equals(s.cityId()))
                    && !s.startTime().isBefore(from) && s.startTime().isBefore(to)) out.add(s);
        }
        return out;
    }

    // ---------------- pricing-service ----------------
    @CircuitBreaker(name = "pricing", fallbackMethod = "quoteFallback")
    public PriceQuote quote(PriceQuoteRequest req) { return pricing.quote(req); }

    /** Цены недоступны — продаём по базовой цене, но продаём. */
    public PriceQuote quoteFallback(PriceQuoteRequest req, Throwable t) {
        log.warn("pricing-service недоступен, считаю по базовой цене: {}", t.toString());
        List<SeatPrice> items = req.seats().stream()
                .map(s -> new SeatPrice(s.row(), s.seat(), req.session().basePrice(), List.of("Базовая цена")))
                .toList();
        int sum = items.stream().mapToInt(SeatPrice::price).sum();
        return new PriceQuote(items, sum, 0, sum, req.promoCode(), false,
                req.promoCode() == null || req.promoCode().isBlank() ? null : "Промокоды временно не применяются");
    }

    @CircuitBreaker(name = "pricing", fallbackMethod = "seatMapFallback")
    public SeatMapPrices seatMap(SessionPricing s) { return pricing.seatMap(s); }

    public SeatMapPrices seatMapFallback(SessionPricing s, Throwable t) {
        int[][] prices = new int[s.rows()][s.seatsPerRow()];
        for (int[] row : prices) java.util.Arrays.fill(row, s.basePrice());
        return new SeatMapPrices(prices, s.basePrice(), s.basePrice(), List.of("Динамические цены временно отключены"));
    }

    public void redeemPromo(String code) {
        try { pricing.redeem(code); } catch (Exception e) { log.warn("Промокод {} не отмечен: {}", code, e.toString()); }
    }

    // ---------------- payment-service ----------------
    @CircuitBreaker(name = "payment", fallbackMethod = "payFallback")
    public PaymentResult pay(PaymentRequest req) { return payments.pay(req); }

    public PaymentResult payFallback(PaymentRequest req, Throwable t) {
        log.error("Оплата не прошла ({}): {}", req.orderRef(), t.toString());
        throw new PaymentUnavailableException(t);
    }

    @CircuitBreaker(name = "payment", fallbackMethod = "refundFallback")
    public PaymentResult refund(RefundRequest req) { return payments.refund(req); }

    public PaymentResult refundFallback(RefundRequest req, Throwable t) {
        log.error("Возврат не прошёл ({}): {}", req.paymentId(), t.toString());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Платёжный сервис сейчас недоступен, возврат оформить не получилось. Попробуйте через минуту");
    }

    // ---------------- concession-service ----------------
    @CircuitBreaker(name = "concession", fallbackMethod = "barQuoteFallback")
    public BarQuote barQuote(BarQuoteRequest req) { return bar.quote(req); }

    /** Бар упал — билет всё равно продаётся, просто без попкорна. */
    public BarQuote barQuoteFallback(BarQuoteRequest req, Throwable t) {
        log.warn("concession-service недоступен: {}", t.toString());
        return null;
    }

    @CircuitBreaker(name = "concession", fallbackMethod = "barCreateFallback")
    public BarOrderView createBarOrder(BarCreate req) { return bar.create(req); }

    public BarOrderView barCreateFallback(BarCreate req, Throwable t) {
        log.warn("Заказ бара не создан: {}", t.toString());
        return null;
    }

    public void cancelBar(Long orderId) {
        try { bar.cancelByBooking(orderId); } catch (Exception e) { log.warn("Не удалось отменить бар: {}", e.toString()); }
    }

    // ---------------- user-service ----------------
    @CircuitBreaker(name = "user", fallbackMethod = "userFallback")
    public UserView user(Long id) { return users.user(id); }

    public UserView userFallback(Long id, Throwable t) { return null; }

    @CircuitBreaker(name = "user", fallbackMethod = "spendFallback")
    public UserView spendPoints(Long userId, Long orderId, int points) {
        return users.spend(userId, new PointsRequest(orderId, points));
    }

    public UserView spendFallback(Long userId, Long orderId, int points, Throwable t) {
        if (t instanceof FeignException.BadRequest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недостаточно баллов");
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Бонусная программа недоступна — оплатите без баллов");
    }

    public void returnPoints(Long userId, Long orderId, int points) {
        if (points <= 0) return;
        try { users.giveBack(userId, new PointsRequest(orderId, points)); }
        catch (Exception e) { log.error("Не удалось вернуть {} баллов пользователю {}: {}", points, userId, e.toString()); }
    }

    /** Платёж не прошёл: сага откатывается, места остаются за зрителем. */
    public static class PaymentUnavailableException extends RuntimeException {
        public PaymentUnavailableException(Throwable cause) { super("Платёжный сервис недоступен", cause); }
    }
}
