package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.*;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.BookingOrder;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.OrderRepository;
import ru.cineflow.booking.repo.TicketRepository;
import ru.cineflow.booking.repo.WaitlistRepository;
import ru.cineflow.booking.service.Views.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Оформление заказа — сага из нескольких сервисов:
 *   1) цены (pricing)  2) бар (concession)  3) списание баллов (user)  4) оплата (payment)
 *   5) билеты  6) заказ бара  7) события в Kafka.
 * Если шаг падает, выполняются компенсации: баллы возвращаются, деньги за бар возвращаются,
 * места остаются за зрителем до конца удержания.
 */
@Service
public class CheckoutService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final SeatService seats;
    private final Remote remote;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final WaitlistRepository waitlist;
    private final EventPublisher publisher;
    private final LiveEvents live;
    private final TicketService ticketService;

    public CheckoutService(SeatService seats, Remote remote, OrderRepository orders, TicketRepository tickets,
                           WaitlistRepository waitlist, EventPublisher publisher, LiveEvents live,
                           TicketService ticketService) {
        this.seats = seats; this.remote = remote; this.orders = orders; this.tickets = tickets;
        this.waitlist = waitlist; this.publisher = publisher; this.live = live; this.ticketService = ticketService;
    }

    private record Prepared(SessionInfo session, PriceQuote price, BarQuote bar, boolean barAvailable,
                            UserView user, int points, int maxPoints, int total, String owner,
                            List<String> messages) {}

    private Prepared prepare(CheckoutRequest req, boolean strict) {
        if (req.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите, чтобы купить билет");
        if (req.seats() == null || req.seats().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите хотя бы одно место");
        SessionInfo s = remote.session(req.sessionId());
        if (s.startTime().isBefore(LocalDateTime.now().minusMinutes(15)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сеанс уже начался");

        String owner = req.groupId() != null && !req.groupId().isBlank()
                ? SeatService.group(req.groupId()) : SeatService.user(req.userId());
        for (Seat seat : req.seats()) {
            String current = seats.owner(req.sessionId(), seat.row(), seat.seat());
            if (!owner.equals(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Время удержания места ряд " + seat.row() + ", место " + seat.seat()
                                + " истекло. Выберите место заново");
            }
        }
        List<String> messages = new ArrayList<>();
        Stats st = seats.stats(req.sessionId());
        double occupancy = (st.sold() + st.held()) / (double) st.total();
        PriceQuote price = remote.quote(new PriceQuoteRequest(
                new SessionPricing(s.startTime(), s.basePrice(), s.rows(), s.seatsPerRow(), occupancy),
                req.seats(), req.promoCode()));
        if (price.promoMessage() != null) messages.add(price.promoMessage());

        UserView user = remote.user(req.userId());
        boolean gold = user != null && "GOLD".equals(user.level());

        BarQuote bar = null;
        boolean barAvailable = true;
        if (req.barItems() != null && !req.barItems().isEmpty()) {
            bar = remote.barQuote(new BarQuoteRequest(req.barItems(), gold));
            if (bar == null) {
                barAvailable = false;
                messages.add("Бар сейчас не принимает заказы — билеты можно купить без него");
            } else if (bar.note() != null) messages.add(bar.note());
        }

        int available = user == null ? 0 : user.points();
        int maxPoints = Math.min(available, price.total() / 2);
        int points = Math.max(0, Math.min(req.usePoints(), maxPoints));
        if (strict && req.usePoints() > maxPoints && req.usePoints() > 0) {
            messages.add("Баллами можно оплатить не больше половины стоимости билетов: спишем " + points);
        }
        int total = price.total() - points + (bar == null ? 0 : bar.total());
        return new Prepared(s, price, bar, barAvailable, user, points, maxPoints, total, owner, messages);
    }

    public QuoteView quote(CheckoutRequest req) {
        Prepared p = prepare(req, false);
        int percent = p.user() == null ? 5 : p.user().percent();
        int earn = (int) Math.round((p.price().total() - p.points() + (p.bar() == null ? 0 : p.bar().total())) * percent / 100.0);
        return new QuoteView(p.session(), p.price(), p.bar(), p.barAvailable(),
                p.user() == null ? 0 : p.user().points(), p.maxPoints(), p.points(),
                p.user() == null ? "BASE" : p.user().level(), p.total(), earn,
                seats.myExpiry(req.sessionId(), p.owner()), p.messages());
    }

    public CheckoutResult checkout(CheckoutRequest req) {
        Prepared p = prepare(req, true);
        SessionInfo s = p.session();

        BookingOrder order = new BookingOrder();
        order.userId = req.userId();
        order.userName = p.user() == null ? "Зритель" : p.user().name();
        order.sessionId = s.id();
        order.groupId = req.groupId();
        order.movieTitle = s.movieTitle();
        order.cinemaName = s.cinemaName();
        order.hallName = s.hallName();
        order.startTime = s.startTime();
        order.ticketsAmount = p.price().subtotal();
        order.discount = p.price().discount();
        order.promoCode = p.price().promoApplied() ? p.price().promoCode() : null;
        order.pointsUsed = p.points();
        order.barAmount = p.bar() == null ? 0 : p.bar().total();
        order.barItemsCount = p.bar() == null ? 0 : p.bar().itemsCount();
        order.popcornCount = p.bar() == null ? 0 : p.bar().popcornCount();
        orders.save(order);
        log.info("Сага заказа #{} начата: сеанс {}, мест {}, к оплате {} ₽", order.id, s.id(), req.seats().size(), p.total());

        // шаг 1: списание баллов
        if (p.points() > 0) {
            try {
                remote.spendPoints(req.userId(), order.id, p.points());
            } catch (RuntimeException e) {
                fail(order, "баллы не списаны");
                throw e;
            }
        }

        // шаг 2: оплата
        PaymentResult payment;
        try {
            payment = remote.pay(new PaymentRequest("order-" + order.id, req.userId(), p.total(),
                    s.movieTitle() + ", " + s.cinemaName()));
        } catch (Remote.PaymentUnavailableException e) {
            remote.returnPoints(req.userId(), order.id, p.points()); // компенсация
            fail(order, "оплата не прошла");
            Long until = seats.myExpiry(s.id(), p.owner());
            String keep = until == null ? "" : " Места остаются за вами до "
                    + HM.format(java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Платёжный сервис временно недоступен, деньги не списаны." + keep);
        }

        // шаг 3: билеты
        order.paymentId = payment.paymentId();
        order.paidAmount = p.total();
        order.status = "PAID";
        List<Ticket> created = new ArrayList<>();
        Map<String, Integer> priceBySeat = new HashMap<>();
        for (SeatPrice sp : p.price().items()) priceBySeat.put(sp.row() + "-" + sp.seat(), sp.price());
        for (Seat seat : req.seats()) {
            Ticket t = new Ticket();
            t.id = UUID.randomUUID().toString();
            t.orderId = order.id;
            t.userId = req.userId();
            t.buyerName = order.userName;
            t.sessionId = s.id();
            t.groupId = req.groupId();
            t.movieId = s.movieId();
            t.movieTitle = s.movieTitle();
            t.genre = s.genre();
            t.durationMin = s.durationMin();
            t.cinemaName = s.cinemaName();
            t.cityName = s.cityName();
            t.hallName = s.hallName();
            t.row = seat.row();
            t.seat = seat.seat();
            t.price = priceBySeat.getOrDefault(seat.row() + "-" + seat.seat(), s.basePrice());
            t.startTime = s.startTime();
            created.add(t);
        }
        tickets.saveAll(created);
        orders.save(order);

        // шаг 4: бар к месту (если бар упал после оплаты — возвращаем деньги за бар)
        String barItems = null;
        if (p.bar() != null && !p.bar().lines().isEmpty()) {
            String seatLabel = req.seats().stream().map(x -> "ряд " + x.row() + ", место " + x.seat())
                    .reduce((a, b) -> a + "; " + b).orElse("");
            BarOrderView bo = remote.createBarOrder(new BarCreate(order.id, req.userId(), s.id(), s.movieTitle(),
                    s.cinemaName(), s.hallName(), seatLabel, s.startTime(), req.barItems(),
                    p.user() != null && "GOLD".equals(p.user().level())));
            if (bo == null) {
                try {
                    remote.refund(new RefundRequest(payment.paymentId(), order.barAmount, "Бар недоступен"));
                    p.messages().add("Бар не принял заказ — вернули " + order.barAmount + " ₽");
                } catch (Exception e) {
                    p.messages().add("Бар не принял заказ — деньги за бар вернём автоматически");
                }
                order.paidAmount -= order.barAmount;
                order.barAmount = 0;
                order.barItemsCount = 0;
                order.popcornCount = 0;
            } else {
                order.barOrderId = bo.id();
                barItems = bo.itemsText();
            }
            orders.save(order);
        }

        // шаг 5: промокод, места, лист ожидания, события
        if (order.promoCode != null) remote.redeemPromo(order.promoCode);
        seats.markSold(s.id(), created, p.owner());
        waitlist.findFirstBySessionIdAndUserIdAndStatusIn(s.id(), req.userId(), List.of("WAITING", "OFFERED"))
                .ifPresent(w -> { w.status = "DONE"; waitlist.save(w); });

        publishPaid(order, created, barItems, s);
        live.publish("/topic/admin/sales", Map.of(
                "orderId", order.id, "movie", s.movieTitle(), "cinema", s.cinemaName(),
                "seats", created.size(), "amount", order.paidAmount, "bar", order.barAmount,
                "at", LocalDateTime.now().toString()));
        log.info("Сага заказа #{} завершена: {} билетов, оплачено {} ₽", order.id, created.size(), order.paidAmount);

        List<TicketView> views = created.stream().map(ticketService::view).toList();
        return new CheckoutResult(order.id, p.total(), order.paidAmount, order.discount, order.pointsUsed,
                order.barAmount, views, barItems, p.messages());
    }

    private void fail(BookingOrder order, String reason) {
        order.status = "FAILED";
        orders.save(order);
        log.warn("Сага заказа #{} откатилась: {}", order.id, reason);
    }

    private void publishPaid(BookingOrder order, List<Ticket> created, String barItems, SessionInfo s) {
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Ticket t : created) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("row", t.row);
            m.put("seat", t.seat);
            m.put("price", t.price);
            ts.add(m);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "ORDER_PAID");
        e.put("orderId", order.id);
        e.put("userId", order.userId);
        e.put("paidAmount", order.paidAmount);
        e.put("pointsUsed", order.pointsUsed);
        e.put("sessionId", s.id());
        e.put("movieTitle", s.movieTitle());
        e.put("cinemaName", s.cinemaName());
        e.put("cinemaAddress", s.cinemaAddress());
        e.put("hallName", s.hallName());
        e.put("startTime", s.startTime().toString());
        e.put("barItems", barItems);
        e.put("tickets", ts);
        publisher.ticketEvent(e);
    }
}
