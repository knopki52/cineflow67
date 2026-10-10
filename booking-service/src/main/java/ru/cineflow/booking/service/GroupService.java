package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.Seat;
import ru.cineflow.booking.client.Dtos.SessionInfo;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.config.BookingProperties;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.GroupBooking;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.GroupRepository;
import ru.cineflow.booking.repo.TicketRepository;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Совместный поход: организатор выбирает места и делится ссылкой,
 * каждый друг выбирает своё кресло и платит сам. Неоплаченные места держатся 10 минут.
 */
@Service
public class GroupService {
    private static final Logger log = LoggerFactory.getLogger(GroupService.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RND = new SecureRandom();

    public record CreateGroup(Long organizerId, String organizerName, Long sessionId, List<Seat> seats) {}
    public record GroupSeat(int row, int seat, String status, String paidBy) {}
    public record GroupView(String id, SessionInfo session, String organizerName, List<GroupSeat> seats,
                            int paid, int total, LocalDateTime expiresAt, boolean expired) {}

    private final GroupRepository groups;
    private final TicketRepository tickets;
    private final SeatService seats;
    private final Remote remote;
    private final LiveEvents live;
    private final BookingProperties props;

    public GroupService(GroupRepository groups, TicketRepository tickets, SeatService seats, Remote remote,
                        LiveEvents live, BookingProperties props) {
        this.groups = groups; this.tickets = tickets; this.seats = seats; this.remote = remote;
        this.live = live; this.props = props;
    }

    public GroupView create(CreateGroup req) {
        if (req.organizerId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите");
        if (req.seats() == null || req.seats().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для совместного похода выберите хотя бы 2 места");
        remote.session(req.sessionId());
        String id = code();
        String g = SeatService.group(id);
        String me = SeatService.user(req.organizerId());
        Duration ttl = Duration.ofMinutes(props.getGroupHoldMinutes());
        Set<String> sold = seats.sold(req.sessionId());
        for (Seat s : req.seats()) {
            String current = seats.owner(req.sessionId(), s.row(), s.seat());
            if (me.equals(current)) {
                seats.transfer(req.sessionId(), s.row(), s.seat(), me, g, ttl);
            } else if (current != null || sold.contains(s.row() + "-" + s.seat())
                    || !seats.tryHold(req.sessionId(), s.row(), s.seat(), g, ttl)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ряд " + s.row() + ", место " + s.seat() + " уже занято");
            }
            live.seat(req.sessionId(), s.row(), s.seat(), "GROUP", g);
        }
        GroupBooking gb = new GroupBooking();
        gb.id = id;
        gb.sessionId = req.sessionId();
        gb.organizerId = req.organizerId();
        gb.organizerName = req.organizerName() == null ? "Друг" : req.organizerName();
        gb.seats = String.join(",", req.seats().stream().map(s -> s.row() + "-" + s.seat()).toList());
        gb.expiresAt = LocalDateTime.now().plus(ttl);
        groups.save(gb);
        log.info("Совместный поход {} создан: сеанс {}, мест {}", id, req.sessionId(), req.seats().size());
        return view(id);
    }

    public GroupView view(String id) {
        GroupBooking gb = groups.findById(id.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Совместный поход не найден"));
        SessionInfo s = remote.session(gb.sessionId);
        Map<String, Ticket> paid = new HashMap<>();
        for (Ticket t : tickets.findByGroupId(gb.id)) {
            if (!"REFUNDED".equals(t.status)) paid.put(t.row + "-" + t.seat, t);
        }
        String g = SeatService.group(gb.id);
        List<GroupSeat> list = new ArrayList<>();
        for (String rs : gb.seats.split(",")) {
            String[] p = rs.split("-");
            int row = Integer.parseInt(p[0]), seat = Integer.parseInt(p[1]);
            Ticket t = paid.get(rs);
            String status;
            if (t != null) status = "PAID";
            else if (g.equals(seats.owner(gb.sessionId, row, seat))) status = "WAITING";
            else status = "RELEASED";
            list.add(new GroupSeat(row, seat, status, t == null ? null : t.buyerName));
        }
        int paidCount = (int) list.stream().filter(x -> "PAID".equals(x.status())).count();
        return new GroupView(gb.id, s, gb.organizerName, list, paidCount, list.size(), gb.expiresAt,
                LocalDateTime.now().isAfter(gb.expiresAt) && paidCount < list.size());
    }

    private static String code() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
