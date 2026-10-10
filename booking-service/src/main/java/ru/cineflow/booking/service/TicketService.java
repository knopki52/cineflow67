package ru.cineflow.booking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.RefundRequest;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.BookingOrder;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.OrderRepository;
import ru.cineflow.booking.repo.TicketRepository;
import ru.cineflow.booking.service.Views.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final Remote remote;
    private final EventPublisher publisher;
    private final LiveEvents live;
    private final ApplicationEventPublisher events;
    private final SeatService seats;

    public TicketService(TicketRepository tickets, OrderRepository orders, Remote remote, EventPublisher publisher,
                         LiveEvents live, ApplicationEventPublisher events, SeatService seats) {
        this.tickets = tickets; this.orders = orders; this.remote = remote; this.publisher = publisher;
        this.live = live; this.events = events; this.seats = seats;
    }

    /** Правила возврата: больше суток — 100%, от 2 до 24 часов — 50%, меньше 2 часов — нельзя. */
    public static int refundPercent(LocalDateTime start) {
        long minutes = Duration.between(LocalDateTime.now(), start).toMinutes();
        if (minutes >= 24 * 60) return 100;
        if (minutes >= 120) return 50;
        return 0;
    }

    private int paidShare(Ticket t) {
        BookingOrder o = orders.findById(t.orderId).orElse(null);
        if (o == null || o.ticketsAmount == 0) return t.price;
        double ratio = (o.ticketsAmount - o.discount - o.pointsUsed) / (double) o.ticketsAmount;
        return (int) Math.round(t.price * Math.max(0, ratio));
    }

    public TicketView view(Ticket t) {
        int pct = "PAID".equals(t.status) ? refundPercent(t.startTime) : 0;
        int estimate = pct == 0 ? 0 : (int) Math.round(paidShare(t) * pct / 100.0);
        return new TicketView(t.id, t.orderId, t.sessionId, t.movieId, t.movieTitle, t.genre, t.cinemaName, t.cityName,
                t.hallName, t.row, t.seat, t.price, t.status, t.refundAmount, t.startTime, t.usedAt, t.groupId,
                t.buyerName, pct, estimate);
    }

    public List<TicketView> byUser(Long userId) {
        return tickets.findByUserIdOrderByStartTimeDesc(userId).stream().map(this::view).toList();
    }

    public Ticket get(String id) {
        return tickets.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Билет не найден"));
    }

    public byte[] qr(String id, int size) {
        Ticket t = get(id);
        try {
            BitMatrix m = new QRCodeWriter().encode("CINEFLOW:" + t.id, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out, new MatrixToImageConfig(0xFF2A1024, 0xFFFFFFFF));
            return out.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось создать QR-код");
        }
    }

    /** Возврат — тоже сага: деньги → билет → бар → баллы → место → лист ожидания. */
    public TicketView refund(String id, Long userId) {
        Ticket t = get(id);
        if (!t.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Это не ваш билет");
        if (!"PAID".equals(t.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот билет нельзя вернуть");
        int pct = refundPercent(t.startTime);
        if (pct == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "До сеанса меньше 2 часов — вернуть билет уже нельзя");
        BookingOrder o = orders.findById(t.orderId).orElseThrow();
        int amount = (int) Math.round(paidShare(t) * pct / 100.0);
        if (amount > 0 && o.paymentId != null) {
            remote.refund(new RefundRequest(o.paymentId, amount, "Возврат билета " + t.id));
        }
        t.status = "REFUNDED";
        t.refundAmount = amount;
        tickets.save(t);

        List<Ticket> all = tickets.findByOrderId(o.id);
        boolean allRefunded = all.stream().allMatch(x -> "REFUNDED".equals(x.status));
        o.status = allRefunded ? "REFUNDED" : "PARTIALLY_REFUNDED";
        orders.save(o);
        if (allRefunded && o.barOrderId != null) remote.cancelBar(o.id);

        double share = o.ticketsAmount == 0 ? 1 : t.price / (double) o.ticketsAmount;
        int pointsBack = (int) Math.round(o.pointsUsed * share * pct / 100.0);
        remote.returnPoints(t.userId, o.id, pointsBack);

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "TICKETS_REFUNDED");
        e.put("userId", t.userId);
        e.put("orderId", o.id);
        e.put("ticketId", t.id);
        e.put("refundAmount", amount);
        e.put("refundPercent", pct);
        e.put("pointsReturned", pointsBack);
        e.put("share", share);
        e.put("movieTitle", t.movieTitle);
        e.put("startTime", t.startTime.toString());
        e.put("row", t.row);
        e.put("seat", t.seat);
        publisher.ticketEvent(e);

        live.seat(t.sessionId, t.row, t.seat, "FREE", null);
        seats.broadcastStats(t.sessionId);
        events.publishEvent(new SeatFreedEvent(t.sessionId, t.row, t.seat, null));
        log.info("Билет {} возвращён: {} ₽ ({}%)", t.id, amount, pct);
        return view(t);
    }

    /** Контролёр на входе. */
    public ScanResult scan(String code, Long sessionId) {
        String id = code == null ? "" : code.trim();
        if (id.startsWith("CINEFLOW:")) id = id.substring("CINEFLOW:".length());
        Ticket t = tickets.findById(id).orElse(null);
        if (t == null) return new ScanResult("NOT_FOUND", "Билет не найден", null);
        if ("REFUNDED".equals(t.status)) return new ScanResult("REFUNDED", "Билет возвращён — проход запрещён", view(t));
        if ("USED".equals(t.status)) {
            return new ScanResult("ALREADY_USED", "Билет уже использован в "
                    + t.usedAt.toLocalTime().withNano(0), view(t));
        }
        if (sessionId != null && !sessionId.equals(t.sessionId)) {
            return new ScanResult("WRONG_SESSION", "Не тот сеанс: билет на «" + t.movieTitle + "» "
                    + t.startTime.toLocalDate() + " в " + t.startTime.toLocalTime(), view(t));
        }
        if (t.startTime.plusMinutes(t.durationMin).isBefore(LocalDateTime.now())) {
            return new ScanResult("EXPIRED", "Сеанс уже закончился", view(t));
        }
        t.status = "USED";
        t.usedAt = LocalDateTime.now();
        tickets.save(t);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "TICKET_USED");
        e.put("userId", t.userId);
        e.put("ticketId", t.id);
        e.put("movieId", t.movieId);
        e.put("movieTitle", t.movieTitle);
        e.put("hallName", t.hallName);
        e.put("row", t.row);
        e.put("seat", t.seat);
        publisher.ticketEvent(e);
        log.info("Проход по билету {}: {}, ряд {}, место {}", t.id, t.hallName, t.row, t.seat);
        return new ScanResult("OK", "Проходите! " + t.hallName + ", ряд " + t.row + ", место " + t.seat, view(t));
    }
}
