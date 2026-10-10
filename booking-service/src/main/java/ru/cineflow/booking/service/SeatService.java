package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.*;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.config.BookingProperties;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.TicketRepository;
import ru.cineflow.booking.service.Views.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Удержание мест в Redis.
 *  hold:{сеанс}:{ряд}-{место} = владелец ("u:12" — зритель, "g:AB12CD" — совместный поход), живёт N минут (TTL).
 *  SET NX гарантирует, что одно место не удержат двое, даже если 100 человек нажали одновременно.
 *  ZSET holds:expiry хранит время истечения, чтобы узнать об освобождении и разбудить лист ожидания.
 */
@Service
public class SeatService {
    private static final Logger log = LoggerFactory.getLogger(SeatService.class);
    public static final String EXPIRY = "holds:expiry";
    private static final List<String> SOLD = List.of("PAID", "USED");

    private final StringRedisTemplate redis;
    private final TicketRepository tickets;
    private final Remote remote;
    private final LiveEvents live;
    private final BookingProperties props;
    private final ApplicationEventPublisher events;

    public SeatService(StringRedisTemplate redis, TicketRepository tickets, Remote remote, LiveEvents live,
                       BookingProperties props, ApplicationEventPublisher events) {
        this.redis = redis; this.tickets = tickets; this.remote = remote; this.live = live;
        this.props = props; this.events = events;
    }

    public static String key(Long sid, int row, int seat) { return "hold:" + sid + ":" + row + "-" + seat; }
    static String member(Long sid, int row, int seat, String owner) { return sid + "|" + row + "-" + seat + "|" + owner; }
    public static String user(Long userId) { return "u:" + userId; }
    public static String group(String groupId) { return "g:" + groupId; }

    public Set<String> sold(Long sid) {
        return tickets.findBySessionIdAndStatusIn(sid, SOLD).stream()
                .map(t -> t.row + "-" + t.seat).collect(Collectors.toSet());
    }

    /** Все удержания сеанса: "ряд-место" → владелец. */
    public Map<String, String> holds(Long sid) {
        String prefix = "hold:" + sid + ":";
        Set<String> keys = redis.keys(prefix + "*");
        Map<String, String> out = new HashMap<>();
        if (keys == null || keys.isEmpty()) return out;
        List<String> list = new ArrayList<>(keys);
        List<String> values = redis.opsForValue().multiGet(list);
        for (int i = 0; i < list.size(); i++) {
            String v = values == null ? null : values.get(i);
            if (v != null) out.put(list.get(i).substring(prefix.length()), v);
        }
        return out;
    }

    public String owner(Long sid, int row, int seat) { return redis.opsForValue().get(key(sid, row, seat)); }

    /** Атомарно занять место. true — место наше (новое удержание или продление своего). */
    public boolean tryHold(Long sid, int row, int seat, String owner, Duration ttl) {
        String k = key(sid, row, seat);
        Boolean ok = redis.opsForValue().setIfAbsent(k, owner, ttl);
        if (!Boolean.TRUE.equals(ok)) {
            if (!owner.equals(redis.opsForValue().get(k))) return false;
            redis.expire(k, ttl);
        }
        redis.opsForZSet().add(EXPIRY, member(sid, row, seat, owner), System.currentTimeMillis() + ttl.toMillis());
        return true;
    }

    /** Передать место другому владельцу (зритель → совместный поход). */
    public void transfer(Long sid, int row, int seat, String from, String to, Duration ttl) {
        redis.opsForValue().set(key(sid, row, seat), to, ttl);
        redis.opsForZSet().remove(EXPIRY, member(sid, row, seat, from));
        redis.opsForZSet().add(EXPIRY, member(sid, row, seat, to), System.currentTimeMillis() + ttl.toMillis());
    }

    public HoldResult hold(Long sid, Long userId, List<Seat> seats) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите, чтобы выбрать места");
        SessionInfo s = remote.session(sid);
        String owner = user(userId);
        Set<String> sold = sold(sid);
        long mine = holds(sid).values().stream().filter(owner::equals).count();
        if (mine + seats.size() > props.getMaxSeatsPerUser()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Можно выбрать не больше " + props.getMaxSeatsPerUser() + " мест");
        }
        Duration ttl = Duration.ofMinutes(props.getHoldMinutes());
        List<Seat> acquired = new ArrayList<>();
        for (Seat seat : seats) {
            validate(s, seat);
            if (sold.contains(seat.row() + "-" + seat.seat()) || !tryHold(sid, seat.row(), seat.seat(), owner, ttl)) {
                for (Seat a : acquired) releaseOne(sid, a.row(), a.seat(), owner, false);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ряд " + seat.row() + ", место " + seat.seat() + " только что занял другой зритель");
            }
            acquired.add(seat);
        }
        for (Seat a : acquired) live.seat(sid, a.row(), a.seat(), "HELD", owner);
        broadcastStats(sid);
        return new HoldResult(acquired, myExpiry(sid, owner));
    }

    public void release(Long sid, Long userId, List<Seat> seats) {
        String owner = user(userId);
        for (Seat seat : seats) releaseOne(sid, seat.row(), seat.seat(), owner, true);
        broadcastStats(sid);
    }

    /** Снять все удержания зрителя на сеансе (кнопка «Отменить выбор»). */
    public void releaseAll(Long sid, Long userId) {
        String owner = user(userId);
        holds(sid).forEach((rs, o) -> {
            if (owner.equals(o)) {
                String[] p = rs.split("-");
                releaseOne(sid, Integer.parseInt(p[0]), Integer.parseInt(p[1]), owner, true);
            }
        });
        broadcastStats(sid);
    }

    private void releaseOne(Long sid, int row, int seat, String owner, boolean notifyWaitlist) {
        String k = key(sid, row, seat);
        if (owner.equals(redis.opsForValue().get(k))) {
            redis.delete(k);
            redis.opsForZSet().remove(EXPIRY, member(sid, row, seat, owner));
            live.seat(sid, row, seat, "FREE", null);
            if (notifyWaitlist) events.publishEvent(new SeatFreedEvent(sid, row, seat, owner));
        }
    }

    /** После оплаты: удержания снимаются, места становятся проданными. */
    public void markSold(Long sid, List<Ticket> sold, String owner) {
        for (Ticket t : sold) {
            String k = key(sid, t.row, t.seat);
            String current = redis.opsForValue().get(k);
            if (current != null) {
                redis.delete(k);
                redis.opsForZSet().remove(EXPIRY, member(sid, t.row, t.seat, current));
            }
            live.seat(sid, t.row, t.seat, "SOLD", owner);
        }
        broadcastStats(sid);
    }

    public Long myExpiry(Long sid, String owner) {
        long min = Long.MAX_VALUE;
        for (Map.Entry<String, String> e : holds(sid).entrySet()) {
            if (!owner.equals(e.getValue())) continue;
            Long ttl = redis.getExpire("hold:" + sid + ":" + e.getKey(), TimeUnit.MILLISECONDS);
            if (ttl != null && ttl > 0) min = Math.min(min, ttl);
        }
        return min == Long.MAX_VALUE ? null : System.currentTimeMillis() + min;
    }

    public SeatMapView map(Long sid, Long userId) {
        SessionInfo s = remote.session(sid);
        Set<String> sold = sold(sid);
        Map<String, String> holds = holds(sid);
        int total = s.rows() * s.seatsPerRow();
        double occupancy = (sold.size() + holds.size()) / (double) total;
        SeatMapPrices prices = remote.seatMap(new SessionPricing(s.startTime(), s.basePrice(), s.rows(), s.seatsPerRow(), occupancy));
        String me = userId == null ? null : user(userId);
        List<Cell> cells = new ArrayList<>(total);
        for (int r = 1; r <= s.rows(); r++) {
            for (int c = 1; c <= s.seatsPerRow(); c++) {
                String rs = r + "-" + c;
                String state;
                String by = null;
                if (sold.contains(rs)) state = "SOLD";
                else if (holds.containsKey(rs)) {
                    by = holds.get(rs);
                    state = by.equals(me) ? "MINE" : by.startsWith("g:") ? "GROUP" : "HELD";
                } else state = "FREE";
                cells.add(new Cell(r, c, state, prices.prices()[r - 1][c - 1], by));
            }
        }
        int percent = (int) Math.round(sold.size() * 100.0 / total);
        return new SeatMapView(s, cells, sold.size(), holds.size(), total, percent, prices.min(), prices.max(),
                prices.sessionFactors(), me == null ? null : myExpiry(sid, me),
                sold.size() + holds.size() >= total);
    }

    public Stats stats(Long sid) {
        SessionInfo s = remote.session(sid);
        int total = s.rows() * s.seatsPerRow();
        int sold = sold(sid).size();
        int held = holds(sid).size();
        return new Stats(sid, sold, held, total, (int) Math.round(sold * 100.0 / total));
    }

    public void broadcastStats(Long sid) {
        try {
            live.publish("/topic/session/" + sid + "/stats", stats(sid));
        } catch (Exception e) {
            log.debug("Статистика не отправлена: {}", e.toString());
        }
    }

    private static void validate(SessionInfo s, Seat seat) {
        if (seat.row() < 1 || seat.row() > s.rows() || seat.seat() < 1 || seat.seat() > s.seatsPerRow()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Такого места нет в зале");
        }
    }

    /**
     * Вызывается планировщиком: находит истёкшие удержания и рассылает «место свободно».
     * ZREM возвращает 1 только одному экземпляру сервиса — так событие не обработается дважды.
     */
    public void processExpired() {
        Set<String> due = redis.opsForZSet().rangeByScore(EXPIRY, 0, System.currentTimeMillis());
        if (due == null || due.isEmpty()) return;
        Set<Long> touched = new HashSet<>();
        for (String m : due) {
            Long removed = redis.opsForZSet().remove(EXPIRY, m);
            if (removed == null || removed == 0) continue;
            String[] parts = m.split("\\|");
            Long sid = Long.parseLong(parts[0]);
            String[] rs = parts[1].split("-");
            int row = Integer.parseInt(rs[0]), seat = Integer.parseInt(rs[1]);
            String owner = parts[2];
            String current = owner(sid, row, seat);
            if (current == null) {
                log.info("Удержание истекло: сеанс {}, ряд {}, место {} ({})", sid, row, seat, owner);
                live.seat(sid, row, seat, "FREE", null);
                events.publishEvent(new SeatFreedEvent(sid, row, seat, owner));
                touched.add(sid);
            } else if (current.equals(owner)) {
                Long ttl = redis.getExpire(key(sid, row, seat), TimeUnit.MILLISECONDS);
                if (ttl != null && ttl > 0) {
                    redis.opsForZSet().add(EXPIRY, m, System.currentTimeMillis() + ttl);
                }
            }
        }
        touched.forEach(this::broadcastStats);
    }
}
