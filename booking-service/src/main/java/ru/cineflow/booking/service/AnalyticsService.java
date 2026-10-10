package ru.cineflow.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.booking.client.Dtos.SessionInfo;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.model.BookingOrder;
import ru.cineflow.booking.model.Ticket;
import ru.cineflow.booking.repo.OrderRepository;
import ru.cineflow.booking.repo.TicketRepository;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Админ-аналитика, демо-данные, «Твой киногод» и внутренние статистики для других сервисов. */
@Service
public class AnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final List<String> SOLD = List.of("PAID", "USED");
    private final Random rnd = new Random();

    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final Remote remote;
    private final SeatService seats;
    private final EventPublisher publisher;
    private final StringRedisTemplate redis;

    public AnalyticsService(TicketRepository tickets, OrderRepository orders, Remote remote, SeatService seats,
                            EventPublisher publisher, StringRedisTemplate redis) {
        this.tickets = tickets; this.orders = orders; this.remote = remote; this.seats = seats;
        this.publisher = publisher; this.redis = redis;
    }

    // ===================== админ-аналитика =====================
    public Map<String, Object> dashboard() {
        LocalDateTime from = LocalDate.now().minusDays(13).atStartOfDay();
        List<BookingOrder> os = orders.findByStatusInAndCreatedAtAfter(List.of("PAID", "PARTIALLY_REFUNDED"), from);
        List<Ticket> ts = tickets.findByStatusInAndCreatedAtAfter(SOLD, from);

        Map<LocalDate, int[]> byDay = new TreeMap<>();
        for (int i = 13; i >= 0; i--) byDay.put(LocalDate.now().minusDays(i), new int[3]);
        for (BookingOrder o : os) {
            int[] d = byDay.get(o.createdAt.toLocalDate());
            if (d == null) continue;
            d[0] += o.paidAmount - o.barAmount;
            d[1] += o.barAmount;
        }
        for (Ticket t : ts) {
            int[] d = byDay.get(t.createdAt.toLocalDate());
            if (d != null) d[2]++;
        }
        List<Map<String, Object>> revenue = new ArrayList<>();
        byDay.forEach((date, d) -> revenue.add(Map.<String, Object>of("date", date.toString(), "tickets", d[0], "bar", d[1], "count", d[2])));

        Map<String, int[]> movies = new HashMap<>();
        for (Ticket t : ts) {
            int[] m = movies.computeIfAbsent(t.movieTitle, k -> new int[2]);
            m[0]++;
            m[1] += t.price;
        }
        List<Map<String, Object>> top = movies.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(5)
                .map(e -> Map.<String, Object>of("title", e.getKey(), "tickets", e.getValue()[0], "revenue", e.getValue()[1]))
                .toList();

        // загруженность залов: средняя заполненность сеансов с продажами
        Map<Long, Long> bySession = ts.stream().filter(t -> t.sessionId != null && t.sessionId > 0)
                .collect(Collectors.groupingBy(t -> t.sessionId, Collectors.counting()));
        Map<String, List<Double>> halls = new HashMap<>();
        bySession.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(150).forEach(e -> {
            try {
                SessionInfo s = remote.session(e.getKey());
                double occ = e.getValue() * 100.0 / (s.rows() * s.seatsPerRow());
                halls.computeIfAbsent(s.cinemaName() + " · " + s.hallName(), k -> new ArrayList<>()).add(occ);
            } catch (Exception ignored) { }
        });
        List<Map<String, Object>> hallList = halls.entrySet().stream()
                .map(e -> Map.<String, Object>of("hall", e.getKey(),
                        "occupancy", (int) Math.round(e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0))))
                .sorted((a, b) -> (Integer) b.get("occupancy") - (Integer) a.get("occupancy"))
                .limit(10).toList();

        int revenueTotal = os.stream().mapToInt(o -> o.paidAmount).sum();
        int barTotal = os.stream().mapToInt(o -> o.barAmount).sum();
        int ordersCount = os.size();
        long barOrders = os.stream().filter(o -> o.barAmount > 0).count();
        int today = os.stream().filter(o -> o.createdAt.toLocalDate().equals(LocalDate.now())).mapToInt(o -> o.paidAmount).sum();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("revenue", revenueTotal);
        totals.put("today", today);
        totals.put("tickets", ts.size());
        totals.put("orders", ordersCount);
        totals.put("avgCheck", ordersCount == 0 ? 0 : revenueTotal / ordersCount);
        totals.put("barRevenue", barTotal);
        totals.put("barShare", revenueTotal == 0 ? 0 : Math.round(barTotal * 100.0 / revenueTotal));
        totals.put("barAttach", ordersCount == 0 ? 0 : Math.round(barOrders * 100.0 / ordersCount));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("revenueByDay", revenue);
        out.put("topMovies", top);
        out.put("halls", hallList);
        return out;
    }

    /** Продажи сеанса по минутам за последние 30 минут (живой график в админке). */
    public List<Map<String, Object>> timeline(Long sessionId) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        Map<LocalDateTime, Integer> buckets = new TreeMap<>();
        for (int i = 29; i >= 0; i--) buckets.put(now.minusMinutes(i), 0);
        for (Ticket t : tickets.findBySessionIdAndStatusIn(sessionId, SOLD)) {
            LocalDateTime m = t.createdAt.withSecond(0).withNano(0);
            buckets.computeIfPresent(m, (k, v) -> v + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.forEach((k, v) -> out.add(Map.<String, Object>of("minute", k.toLocalTime().toString(), "sold", v)));
        return out;
    }

    // ===================== внутренние статистики =====================
    public List<Long> userMovieIds(Long userId) {
        return tickets.findByUserIdOrderByStartTimeDesc(userId).stream()
                .filter(t -> SOLD.contains(t.status))
                .sorted(Comparator.comparing(t -> t.createdAt))
                .map(t -> t.movieId).distinct().toList();
    }

    public boolean attended(Long userId, Long movieId) {
        return tickets.existsByUserIdAndMovieIdAndStatus(userId, movieId, "USED");
    }

    public List<Long> topMovies(int days) {
        return tickets.findByStatusInAndCreatedAtAfter(SOLD, LocalDateTime.now().minusDays(days)).stream()
                .collect(Collectors.groupingBy(t -> t.movieId, Collectors.counting()))
                .entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey).toList();
    }

    // ===================== «Твой киногод» =====================
    public Map<String, Object> wrapped(Long userId) {
        int year = LocalDate.now().getYear();
        List<Ticket> list = tickets.findByUserIdOrderByStartTimeDesc(userId).stream()
                .filter(t -> SOLD.contains(t.status) && t.startTime.getYear() == year)
                .sorted(Comparator.comparing(t -> t.startTime)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("films", list.size());
        if (list.isEmpty()) return out;

        Map<String, Long> genres = list.stream().collect(Collectors.groupingBy(t -> t.genre, Collectors.counting()));
        String favGenre = genres.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        long night = list.stream().filter(t -> t.startTime.getHour() >= 21 || t.startTime.getHour() < 4).count();
        long morning = list.stream().filter(t -> t.startTime.getHour() < 12 && t.startTime.getHour() >= 4).count();
        long horror = genres.getOrDefault("Ужасы", 0L);
        double avgRow = list.stream().mapToInt(t -> t.row).average().orElse(5);
        Map<String, Long> seatsCount = list.stream().collect(Collectors.groupingBy(t -> t.row + "-" + t.seat, Collectors.counting()));
        int favRow = (int) Math.round(avgRow);
        double avgSeatShare = list.stream().mapToDouble(t -> t.seat / 12.0).average().orElse(0.5);
        String zone = avgSeatShare < 0.38 ? "слева" : avgSeatShare > 0.62 ? "справа" : "по центру";

        List<BookingOrder> os = orders.findByUserIdAndStatusIn(userId, List.of("PAID", "PARTIALLY_REFUNDED"));
        int popcorn = os.stream().mapToInt(o -> o.popcornCount).sum();
        int spent = os.stream().mapToInt(o -> o.paidAmount).sum();
        String topCinema = list.stream().collect(Collectors.groupingBy(t -> t.cinemaName, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        Map<String, Long> movieCount = list.stream().collect(Collectors.groupingBy(t -> t.movieTitle, Collectors.counting()));
        Map.Entry<String, Long> topMovie = movieCount.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);

        String[] type = kinotype(list.size(), favGenre, horror, night, morning, avgRow);
        out.put("uniqueMovies", movieCount.size());
        out.put("hours", Math.round(list.stream().mapToInt(t -> t.durationMin).sum() / 60.0));
        out.put("favoriteGenre", favGenre);
        out.put("favoriteGenreShare", Math.round(genres.getOrDefault(favGenre, 0L) * 100.0 / list.size()));
        out.put("genres", genres.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> Map.of("genre", e.getKey(), "count", e.getValue())).toList());
        out.put("favoriteRow", favRow);
        out.put("favoriteZone", zone);
        out.put("sameSeatTimes", seatsCount.values().stream().max(Long::compare).orElse(1L));
        out.put("popcorn", popcorn);
        out.put("nightSessions", night);
        out.put("morningSessions", morning);
        out.put("topCinema", topCinema);
        out.put("topMovie", topMovie == null ? null : topMovie.getKey());
        out.put("topMovieTimes", topMovie == null ? 0 : topMovie.getValue());
        out.put("totalSpent", spent);
        out.put("firstMovie", list.get(0).movieTitle);
        out.put("firstDate", list.get(0).startTime.toLocalDate().toString());
        out.put("kinotype", type[0]);
        out.put("kinotypeEmoji", type[1]);
        out.put("kinotypeText", type[2]);
        return out;
    }

    private static String[] kinotype(int total, String genre, long horror, long night, long morning, double avgRow) {
        if (horror * 100 / total >= 30 && night * 100 / total >= 30)
            return new String[]{"Ночной охотник за ужастиками", "🦇", "Чем темнее за окном, тем страшнее на экране. Идеальный вечер."};
        if (night * 100 / total >= 40)
            return new String[]{"Сова полуночного сеанса", "🦉", "Последний сеанс — ваш любимый. Пустой зал и никаких спойлеров."};
        if (morning * 100 / total >= 40)
            return new String[]{"Утренний ценитель", "☀️", "Кино с кофе и полупустым залом — и выгодно, и спокойно."};
        if (avgRow <= 2.5)
            return new String[]{"Смельчак первого ряда", "🎢", "Экран на весь мир, шея — дело второе."};
        return switch (genre) {
            case "Фантастика" -> new String[]{"Покоритель галактик", "🚀", "Червоточины, пустыни и симуляции — вы видели всю вселенную."};
            case "Комедия" -> new String[]{"Главный по хорошему настроению", "😂", "Вы точно знаете, зачем ходить в кино."};
            case "Ужасы" -> new String[]{"Любитель пощекотать нервы", "👻", "Свет гаснет, сердце стучит — то, что нужно."};
            case "Драма" -> new String[]{"Тонкий ценитель драмы", "🎭", "Платочек всегда с собой."};
            case "Триллер" -> new String[]{"Мастер напряжения", "🕵️", "Догадываетесь о развязке раньше героев."};
            case "Мультфильм" -> new String[]{"Взрослый, который верит в чудо", "✨", "Анимация — это не только для детей, и вы это знаете."};
            case "Боевик" -> new String[]{"Адреналиновый зритель", "💥", "Погони, взрывы, громкий звук — дайте два."};
            default -> new String[]{"Киноман широкого профиля", "🎬", "Смотрите всё — и правильно делаете."};
        };
    }

    // ===================== демо-данные =====================
    /** Заполняет историю продаж за 2 недели — чтобы графики в админке были живыми. */
    public Map<String, Object> seedDemo(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<SessionInfo> sessions = remote.sessions(null, now, now.plusDays(3));
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Нет сеансов");
        Map<Long, Set<String>> taken = new HashMap<>();
        int created = 0;
        for (int i = 0; i < 160; i++) {
            SessionInfo s = sessions.get(weightedIndex(sessions));
            Set<String> busy = taken.computeIfAbsent(s.id(), k -> new HashSet<>(seats.sold(k)));
            busy.addAll(seats.holds(s.id()).keySet());
            int n = 1 + rnd.nextInt(3);
            LocalDateTime createdAt = now.minusDays(rnd.nextInt(14)).minusMinutes(rnd.nextInt(600));
            if (createdAt.isAfter(now)) createdAt = now.minusMinutes(5);
            List<Ticket> ts = new ArrayList<>();
            int startSeat = 1 + rnd.nextInt(Math.max(1, s.seatsPerRow() - n));
            int row = 1 + rnd.nextInt(s.rows());
            for (int k = 0; k < n; k++) {
                String rs = row + "-" + (startSeat + k);
                if (busy.contains(rs) || startSeat + k > s.seatsPerRow()) continue;
                busy.add(rs);
                ts.add(ticket(s, row, startSeat + k, 500000L + rnd.nextInt(60), "Зритель", createdAt));
            }
            if (ts.isEmpty()) continue;
            boolean bar = rnd.nextInt(100) < 45;
            BookingOrder o = order(s, ts, 500000L + rnd.nextInt(60), createdAt, bar ? 290 + rnd.nextInt(6) * 80 : 0, bar ? 1 + rnd.nextInt(2) : 0);
            ts.forEach(t -> { t.orderId = o.id; t.userId = o.userId; });
            tickets.saveAll(ts);
            created += ts.size();
        }
        int personal = userId != null ? seedPersonal(userId, sessions) : 0;
        log.info("Демо-данные: {} билетов в аналитике, {} в личной истории", created, personal);
        return Map.<String, Object>of("tickets", created, "personalTickets", personal);
    }

    /** Личная история зрителя за год: для «Киногода», рекомендаций и отзывов. */
    private int seedPersonal(Long userId, List<SessionInfo> sessions) {
        UserViewName user = new UserViewName(remote.user(userId));
        Map<Long, SessionInfo> byMovie = new LinkedHashMap<>();
        for (SessionInfo s : sessions) byMovie.putIfAbsent(s.movieId(), s);
        List<SessionInfo> movies = new ArrayList<>(byMovie.values());
        movies.sort(Comparator.comparing((SessionInfo s) -> !"Фантастика".equals(s.genre())));
        int count = 0;
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), 1, 15);
        long span = Math.max(30, Duration.between(start.atStartOfDay(), LocalDateTime.now().minusDays(2)).toDays());
        for (int i = 0; i < 14; i++) {
            SessionInfo s = movies.get(i < 6 ? i % Math.min(3, movies.size()) : rnd.nextInt(movies.size()));
            LocalDateTime when = start.plusDays(span * i / 14).atTime(i % 3 == 0 ? 22 : 19, i % 2 == 0 ? 30 : 0);
            Ticket t = ticket(s, 4 + rnd.nextInt(3), 5 + rnd.nextInt(4), userId, user.name(), when.minusDays(2));
            t.sessionId = 0L;
            t.startTime = when;
            t.status = "USED";
            t.usedAt = when.minusMinutes(10);
            BookingOrder o = order(s, List.of(t), userId, when.minusDays(2), i % 2 == 0 ? 340 : 0, i % 2 == 0 ? 1 : 0);
            o.userName = user.name();
            o.startTime = when;
            orders.save(o);
            t.orderId = o.id;
            tickets.save(t);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "ORDER_PAID");
            e.put("demo", true);
            e.put("orderId", o.id);
            e.put("userId", userId);
            e.put("paidAmount", o.paidAmount);
            publisher.ticketEvent(e);
            count++;
        }
        return count;
    }

    private record UserViewName(String name) {
        UserViewName(ru.cineflow.booking.client.Dtos.UserView u) { this(u == null ? "Зритель" : u.name()); }
    }

    /** Почти полный зал: все места проданы, а 2 «зависших» удержания освободятся через 2 минуты. */
    public Map<String, Object> fillSession(Long sessionId) {
        SessionInfo s = sessionId != null ? remote.session(sessionId) : pickEvening();
        Set<String> busy = new HashSet<>(seats.sold(s.id()));
        busy.addAll(seats.holds(s.id()).keySet());
        List<Ticket> ts = new ArrayList<>();
        int lastRow = s.rows();
        int midA = s.seatsPerRow() / 2, midB = midA + 1;
        for (int r = 1; r <= s.rows(); r++) {
            for (int c = 1; c <= s.seatsPerRow(); c++) {
                String rs = r + "-" + c;
                if (busy.contains(rs)) continue;
                if (r == lastRow - 2 && (c == midA || c == midB)) continue; // эти два держит «другой зритель»
                ts.add(ticket(s, r, c, 600000L + rnd.nextInt(80), "Зритель", LocalDateTime.now().minusMinutes(rnd.nextInt(180))));
            }
        }
        if (!ts.isEmpty()) {
            BookingOrder o = order(s, ts, 600000L, LocalDateTime.now().minusMinutes(30), 0, 0);
            ts.forEach(t -> t.orderId = o.id);
            tickets.saveAll(ts);
        }
        String ghost = SeatService.user(999999L);
        seats.tryHold(s.id(), lastRow - 2, midA, ghost, Duration.ofMinutes(2));
        seats.tryHold(s.id(), lastRow - 2, midB, ghost, Duration.ofMinutes(2));
        seats.broadcastStats(s.id());
        log.info("Сеанс {} заполнен для демо листа ожидания", s.id());
        return Map.<String, Object>of("sessionId", s.id(), "movie", s.movieTitle(), "cinema", s.cinemaName(), "hall", s.hallName(),
                "startTime", s.startTime().toString(),
                "note", "Зал полный. Через 2 минуты освободятся 2 места в ряду " + (lastRow - 2));
    }

    private SessionInfo pickEvening() {
        LocalDateTime now = LocalDateTime.now();
        return remote.sessions(1L, now.plusHours(3), now.plusDays(2)).stream()
                .filter(x -> x.startTime().getHour() >= 18 && x.rows() * x.seatsPerRow() <= 96)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Не нашли подходящий вечерний сеанс"));
    }

    private int weightedIndex(List<SessionInfo> list) {
        for (int i = 0; i < 4; i++) {
            int idx = rnd.nextInt(list.size());
            if (list.get(idx).startTime().getHour() >= 17) return idx;
        }
        return rnd.nextInt(list.size());
    }

    private Ticket ticket(SessionInfo s, int row, int seat, Long userId, String name, LocalDateTime createdAt) {
        Ticket t = new Ticket();
        t.id = UUID.randomUUID().toString();
        t.userId = userId;
        t.buyerName = name;
        t.sessionId = s.id();
        t.movieId = s.movieId();
        t.movieTitle = s.movieTitle();
        t.genre = s.genre();
        t.durationMin = s.durationMin();
        t.cinemaName = s.cinemaName();
        t.cityName = s.cityName();
        t.hallName = s.hallName();
        t.row = row;
        t.seat = seat;
        t.price = (int) (Math.round(s.basePrice() * (0.8 + rnd.nextInt(5) * 0.1) / 10.0) * 10);
        t.startTime = s.startTime();
        t.createdAt = createdAt;
        t.reminded = true;
        return t;
    }

    private BookingOrder order(SessionInfo s, List<Ticket> ts, Long userId, LocalDateTime createdAt, int bar, int popcorn) {
        BookingOrder o = new BookingOrder();
        o.userId = userId;
        o.userName = "Зритель";
        o.sessionId = s.id();
        o.movieTitle = s.movieTitle();
        o.cinemaName = s.cinemaName();
        o.hallName = s.hallName();
        o.startTime = s.startTime();
        o.ticketsAmount = ts.stream().mapToInt(t -> t.price).sum();
        o.barAmount = bar;
        o.popcornCount = popcorn;
        o.barItemsCount = popcorn;
        o.paidAmount = o.ticketsAmount + bar;
        o.paymentId = "demo";
        o.status = "PAID";
        o.createdAt = createdAt;
        return orders.save(o);
    }
}
