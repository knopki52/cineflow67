package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.SessionInfo;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.config.BookingProperties;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.WaitlistEntry;
import ru.cineflow.booking.repo.WaitlistRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Лист ожидания на аншлаговый сеанс. */
@Service
public class WaitlistService {
    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    public record Position(Long entryId, String status, int position, int queue, Integer offeredRow, Integer offeredSeat,
                           LocalDateTime offerExpiresAt) {}

    private final WaitlistRepository repo;
    private final SeatService seats;
    private final Remote remote;
    private final EventPublisher publisher;
    private final LiveEvents live;
    private final BookingProperties props;

    public WaitlistService(WaitlistRepository repo, SeatService seats, Remote remote, EventPublisher publisher,
                           LiveEvents live, BookingProperties props) {
        this.repo = repo; this.seats = seats; this.remote = remote; this.publisher = publisher;
        this.live = live; this.props = props;
    }

    public Position join(Long sessionId, Long userId, String userName) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите, чтобы встать в очередь");
        WaitlistEntry e = repo.findFirstBySessionIdAndUserIdAndStatusIn(sessionId, userId, List.of("WAITING", "OFFERED"))
                .orElseGet(() -> {
                    WaitlistEntry n = new WaitlistEntry();
                    n.sessionId = sessionId;
                    n.userId = userId;
                    n.userName = userName;
                    log.info("Пользователь {} встал в лист ожидания сеанса {}", userId, sessionId);
                    return repo.save(n);
                });
        return position(e);
    }

    public Position status(Long sessionId, Long userId) {
        return repo.findFirstBySessionIdAndUserIdAndStatusIn(sessionId, userId, List.of("WAITING", "OFFERED"))
                .map(this::position).orElse(null);
    }

    public void leave(Long sessionId, Long userId) {
        repo.findFirstBySessionIdAndUserIdAndStatusIn(sessionId, userId, List.of("WAITING", "OFFERED"))
                .ifPresent(e -> { e.status = "CANCELLED"; repo.save(e); });
    }

    private Position position(WaitlistEntry e) {
        List<WaitlistEntry> queue = repo.findBySessionIdAndStatusOrderByCreatedAtAsc(e.sessionId, "WAITING");
        int pos = 0;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id.equals(e.id)) pos = i + 1;
        return new Position(e.id, e.status, pos, queue.size(), e.offeredRow, e.offeredSeat, e.offerExpiresAt);
    }

    /** Освободилось место — отдаём первому в очереди и держим за ним 10 минут. */
    @EventListener
    public synchronized void onSeatFreed(SeatFreedEvent ev) {
        // истёкшее предложение — переводим в EXPIRED, место уйдёт следующему
        if (ev.previousOwner() != null && ev.previousOwner().startsWith("u:")) {
            Long uid = Long.parseLong(ev.previousOwner().substring(2));
            repo.findFirstBySessionIdAndUserIdAndStatusIn(ev.sessionId(), uid, List.of("OFFERED")).ifPresent(e -> {
                if (Integer.valueOf(ev.row()).equals(e.offeredRow) && Integer.valueOf(ev.seat()).equals(e.offeredSeat)) {
                    e.status = "EXPIRED";
                    repo.save(e);
                    log.info("Предложение из листа ожидания для {} истекло", uid);
                }
            });
        }
        WaitlistEntry next = repo.findFirstBySessionIdAndStatusOrderByCreatedAtAsc(ev.sessionId(), "WAITING").orElse(null);
        if (next == null) return;
        Duration ttl = Duration.ofMinutes(props.getWaitlistHoldMinutes());
        if (!seats.tryHold(ev.sessionId(), ev.row(), ev.seat(), SeatService.user(next.userId), ttl)) return;
        next.status = "OFFERED";
        next.offeredRow = ev.row();
        next.offeredSeat = ev.seat();
        next.offerExpiresAt = LocalDateTime.now().plus(ttl);
        repo.save(next);
        live.seat(ev.sessionId(), ev.row(), ev.seat(), "HELD", SeatService.user(next.userId));
        seats.broadcastStats(ev.sessionId());

        SessionInfo s = remote.session(ev.sessionId());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "WAITLIST_OFFER");
        e.put("userId", next.userId);
        e.put("sessionId", s.id());
        e.put("movieTitle", s.movieTitle());
        e.put("cinemaName", s.cinemaName());
        e.put("hallName", s.hallName());
        e.put("startTime", s.startTime().toString());
        e.put("row", ev.row());
        e.put("seat", ev.seat());
        e.put("holdMinutes", props.getWaitlistHoldMinutes());
        publisher.notify(e);
        log.info("Место ряд {}, место {} сеанса {} предложено {} из листа ожидания",
                ev.row(), ev.seat(), ev.sessionId(), next.userId);
    }
}
