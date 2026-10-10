package ru.cineflow.bar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BarService {
    private static final Logger log = LoggerFactory.getLogger(BarService.class);

    public record Line(Long itemId, int qty) {}
    public record QuoteRequest(List<Line> items, boolean freePopcorn) {}
    public record QuoteLine(Long itemId, String name, String emoji, int qty, int price, int sum) {}
    public record Quote(List<QuoteLine> lines, int total, int discount, int itemsCount, int popcornCount, String note) {}
    public record CreateOrder(Long bookingOrderId, Long userId, Long sessionId, String movieTitle, String cinemaName,
                              String hallName, String seatLabel, LocalDateTime startTime,
                              List<Line> items, boolean freePopcorn) {}

    private final MenuRepository menu;
    private final BarOrderRepository orders;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public BarService(MenuRepository menu, BarOrderRepository orders, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.menu = menu; this.orders = orders; this.kafka = kafka; this.json = json;
    }

    /** Gold-уровень: один попкорн в заказе бесплатно. */
    public Quote quote(QuoteRequest req) {
        Map<Long, MenuItem> byId = menu.findAll().stream().collect(Collectors.toMap(m -> m.id, Function.identity()));
        List<QuoteLine> lines = new ArrayList<>();
        int total = 0, count = 0, popcorn = 0, cheapestPopcorn = Integer.MAX_VALUE;
        for (Line l : req.items() == null ? List.<Line>of() : req.items()) {
            MenuItem m = byId.get(l.itemId());
            if (m == null || l.qty() <= 0) continue;
            int sum = m.price * l.qty();
            lines.add(new QuoteLine(m.id, m.name, m.emoji, l.qty(), m.price, sum));
            total += sum;
            count += l.qty();
            if ("POPCORN".equals(m.category) || "COMBO".equals(m.category)) {
                popcorn += l.qty();
                if ("POPCORN".equals(m.category)) cheapestPopcorn = Math.min(cheapestPopcorn, m.price);
            }
        }
        int discount = 0;
        String note = null;
        if (req.freePopcorn() && cheapestPopcorn != Integer.MAX_VALUE) {
            discount = cheapestPopcorn;
            note = "Попкорн в подарок для уровня Gold";
        }
        return new Quote(lines, total - discount, discount, count, popcorn, note);
    }

    @Transactional
    public BarOrder create(CreateOrder req) {
        Quote q = quote(new QuoteRequest(req.items(), req.freePopcorn()));
        if (q.lines().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой заказ");
        BarOrder o = new BarOrder();
        o.bookingOrderId = req.bookingOrderId();
        o.userId = req.userId();
        o.sessionId = req.sessionId();
        o.movieTitle = req.movieTitle();
        o.cinemaName = req.cinemaName();
        o.hallName = req.hallName();
        o.seatLabel = req.seatLabel();
        o.startTime = req.startTime();
        o.itemsText = q.lines().stream().map(l -> l.emoji() + " " + l.name() + " ×" + l.qty())
                .collect(Collectors.joining(", "));
        o.itemsCount = q.itemsCount();
        o.popcornCount = q.popcornCount();
        o.total = q.total();
        orders.save(o);
        log.info("Заказ бара #{} к месту «{}, {}»: {}", o.id, o.hallName, o.seatLabel, o.itemsText);
        return o;
    }

    @Transactional
    public BarOrder setStatus(Long id, String status) {
        BarOrder o = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        o.status = status;
        log.info("Заказ бара #{} → {}", id, status);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "BAR_STATUS");
        event.put("userId", o.userId);
        event.put("barOrderId", o.id);
        event.put("status", status);
        event.put("items", o.itemsText);
        event.put("hallName", o.hallName);
        event.put("seatLabel", o.seatLabel);
        event.put("movieTitle", o.movieTitle);
        try {
            kafka.send("notifications", String.valueOf(o.userId), json.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление: {}", e.toString());
        }
        return o;
    }

    /** Компенсация саги при возврате билетов: отменяем заказ бара. */
    @Transactional
    public List<BarOrder> cancelByBooking(Long bookingOrderId) {
        List<BarOrder> list = orders.findByBookingOrderId(bookingOrderId);
        for (BarOrder o : list) {
            if (!"DELIVERED".equals(o.status)) o.status = "CANCELLED";
        }
        log.info("Отменены заказы бара по брони {}: {}", bookingOrderId, list.size());
        return list;
    }
}
