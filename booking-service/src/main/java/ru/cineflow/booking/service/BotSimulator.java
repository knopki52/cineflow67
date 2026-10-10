package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.cineflow.booking.client.Dtos.BarLine;
import ru.cineflow.booking.client.Dtos.Seat;
import ru.cineflow.booking.client.Dtos.SessionInfo;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.live.CursorController.CursorMessage;
import ru.cineflow.booking.live.LiveEvents;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.TicketRepository;
import ru.cineflow.booking.service.Views.CheckoutRequest;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Демо «100 зрителей одновременно»: виртуальные потоки Java 21 ведут курсоры по залу,
 * дерутся за лучшие места в центре и оплачивают. В конце считаем двойные продажи — их ноль.
 */
@Service
public class BotSimulator {
    private static final Logger log = LoggerFactory.getLogger(BotSimulator.class);
    private static final String[] NAMES = {"Аня", "Миша", "Катя", "Дима", "Оля", "Саша", "Женя", "Лера", "Гоша",
            "Ира", "Паша", "Таня", "Рома", "Вера", "Тёма", "Ника", "Глеб", "Яна", "Лёша", "Маша"};

    public record BotStatus(boolean running, Long sessionId, int bots, int finished, int success,
                            int conflicts, int failed, int doubleSales) {}

    private final SeatService seats;
    private final CheckoutService checkout;
    private final Remote remote;
    private final LiveEvents live;
    private final TicketRepository tickets;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger finished = new AtomicInteger(), success = new AtomicInteger(),
            conflicts = new AtomicInteger(), failed = new AtomicInteger();
    private volatile Long sessionId;
    private volatile int bots;
    private volatile int doubles;

    public BotSimulator(SeatService seats, CheckoutService checkout, Remote remote, LiveEvents live, TicketRepository tickets) {
        this.seats = seats; this.checkout = checkout; this.remote = remote; this.live = live; this.tickets = tickets;
    }

    public BotStatus status() {
        return new BotStatus(running.get(), sessionId, bots, finished.get(), success.get(), conflicts.get(), failed.get(), doubles);
    }

    public BotStatus start(Long sid, int count) {
        if (!running.compareAndSet(false, true)) return status();
        SessionInfo s = remote.session(sid);
        sessionId = sid;
        bots = Math.max(1, Math.min(count, 200));
        doubles = 0;
        finished.set(0); success.set(0); conflicts.set(0); failed.set(0);
        log.info("Запускаю {} ботов-зрителей на сеанс {}", bots, sid);
        Thread.ofVirtual().start(() -> {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < bots; i++) {
                    final int n = i;
                    pool.submit(() -> runBot(s, n));
                }
            }
            doubles = countDoubles(sid);
            running.set(false);
            log.info("Боты закончили: купили {}, конфликтов {}, ошибок {}, двойных продаж {}",
                    success.get(), conflicts.get(), failed.get(), doubles);
            push();
        });
        push();
        return status();
    }

    private void runBot(SessionInfo s, int n) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long userId = 900000L + n;
        String clientId = "bot-" + n;
        String name = NAMES[n % NAMES.length] + " · бот";
        String color = "hsl(" + r.nextInt(360) + " 85% 62%)";
        double x = r.nextDouble() * s.seatsPerRow(), y = s.rows() + 1.5;
        try {
            Thread.sleep(r.nextInt(9000));
            for (int attempt = 0; attempt < 4; attempt++) {
                Seat target = pickSeat(s, r);
                if (target == null) break;
                // плавно ведём курсор к креслу
                double tx = target.seat() - 0.5, ty = target.row() - 0.5;
                for (int step = 1; step <= 10; step++) {
                    double k = step / 10.0;
                    cursor(s.id(), clientId, name, color, x + (tx - x) * k, y + (ty - y) * k, null);
                    Thread.sleep(70 + r.nextInt(60));
                }
                x = tx; y = ty;
                try {
                    seats.hold(s.id(), userId, List.of(target));
                } catch (Exception conflict) {
                    conflicts.incrementAndGet();
                    push();
                    continue;
                }
                cursor(s.id(), clientId, name, color, x, y, target);
                Thread.sleep(400 + r.nextInt(1500));
                try {
                    List<BarLine> bar = r.nextInt(100) < 30 ? List.of(new BarLine(1L, 1)) : List.of();
                    checkout.checkout(new CheckoutRequest(userId, s.id(), List.of(target), null, 0, bar, null));
                    success.incrementAndGet();
                } catch (Exception payFail) {
                    failed.incrementAndGet();
                    seats.release(s.id(), userId, List.of(target));
                }
                break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failed.incrementAndGet();
        } finally {
            live.publish("/topic/session/" + s.id() + "/cursors",
                    new CursorMessage(s.id(), clientId, name, color, x, y, null, null, false, true));
            finished.incrementAndGet();
            push();
        }
    }

    /** Боты специально целятся в лучшие места по центру — так конфликтов больше, а двойных продаж всё равно ноль. */
    private Seat pickSeat(SessionInfo s, ThreadLocalRandom r) {
        Set<String> busy = new HashSet<>(seats.sold(s.id()));
        busy.addAll(seats.holds(s.id()).keySet());
        double cr = s.rows() * 0.6, cc = (s.seatsPerRow() + 1) / 2.0;
        List<Seat> free = new ArrayList<>();
        for (int row = 1; row <= s.rows(); row++)
            for (int c = 1; c <= s.seatsPerRow(); c++)
                if (!busy.contains(row + "-" + c)) free.add(new Seat(row, c));
        if (free.isEmpty()) return null;
        free.sort(Comparator.comparingDouble(seat -> Math.hypot(seat.row() - cr, (seat.seat() - cc) * 0.7)));
        int pool = Math.min(free.size(), 12);
        return free.get(r.nextInt(pool));
    }

    private void cursor(Long sid, String id, String name, String color, double x, double y, Seat seat) {
        live.publish("/topic/session/" + sid + "/cursors", new CursorMessage(sid, id, name, color, x, y,
                seat == null ? null : seat.row(), seat == null ? null : seat.seat(), false, false));
    }

    private int countDoubles(Long sid) {
        Map<String, Long> counts = tickets.findBySessionIdAndStatusIn(sid, List.of("PAID", "USED")).stream()
                .collect(Collectors.groupingBy((Ticket t) -> t.row + "-" + t.seat, Collectors.counting()));
        return (int) counts.values().stream().filter(c -> c > 1).count();
    }

    private void push() {
        live.publish("/topic/admin/bots", status());
    }
}
