package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.TicketRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class Schedulers {
    private static final Logger log = LoggerFactory.getLogger(Schedulers.class);

    private final SeatService seats;
    private final TicketRepository tickets;
    private final EventPublisher publisher;

    public Schedulers(SeatService seats, TicketRepository tickets, EventPublisher publisher) {
        this.seats = seats; this.tickets = tickets; this.publisher = publisher;
    }

    /** Каждые 2 секунды: истёкшие удержания → «место свободно» всем зрителям + лист ожидания. */
    @Scheduled(fixedDelay = 2000, initialDelay = 10000)
    public void expireHolds() {
        try {
            seats.processExpired();
        } catch (Exception e) {
            log.warn("Обработка удержаний: {}", e.toString());
        }
    }

    /** Напоминание за час до сеанса (уходит в Telegram и на почту через notification-service). */
    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void reminders() {
        LocalDateTime now = LocalDateTime.now();
        for (Ticket t : tickets.findByStatusAndRemindedFalseAndStartTimeBetween("PAID", now.plusMinutes(40), now.plusMinutes(65))) {
            if (tickets.markReminded(t.id) == 0) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "REMINDER");
            e.put("userId", t.userId);
            e.put("ticketId", t.id);
            e.put("movieTitle", t.movieTitle);
            e.put("cinemaName", t.cinemaName);
            e.put("hallName", t.hallName);
            e.put("row", t.row);
            e.put("seat", t.seat);
            e.put("startTime", t.startTime.toString());
            publisher.notify(e);
            log.info("Напоминание о сеансе отправлено: билет {}", t.id);
        }
    }
}
