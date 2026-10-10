package ru.cineflow.booking.service;

import org.springframework.stereotype.Service;
import ru.cineflow.booking.client.Dtos.*;
import ru.cineflow.booking.client.Remote;
import ru.cineflow.booking.service.Views.Stats;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Киноассистент без LLM: разбирает запрос на русском («комедию в пятницу вечером, недорого, вдвоём»)
 * правилами и ключевыми словами, ищет сеансы и подбирает N свободных мест рядом.
 */
@Service
public class AssistantService {

    public record AssistantRequest(String text, Long cityId, Long userId) {}
    public record Understanding(String genre, String movie, String day, String time, int count,
                                boolean cheap, boolean imax, List<String> chips) {}
    public record Suggestion(SessionInfo session, List<Seat> seats, int total, String why) {}
    public record AssistantReply(String reply, Understanding understood, List<Suggestion> suggestions) {}

    private static final Map<String, String> GENRES = new LinkedHashMap<>();
    static {
        GENRES.put("комеди", "Комедия"); GENRES.put("смешн", "Комедия"); GENRES.put("поржать", "Комедия");
        GENRES.put("посмеят", "Комедия");
        GENRES.put("ужас", "Ужасы"); GENRES.put("хоррор", "Ужасы"); GENRES.put("страшн", "Ужасы"); GENRES.put("жутк", "Ужасы");
        GENRES.put("фантаст", "Фантастика"); GENRES.put("космос", "Фантастика"); GENRES.put("sci-fi", "Фантастика");
        GENRES.put("триллер", "Триллер"); GENRES.put("напряжен", "Триллер"); GENRES.put("детектив", "Триллер");
        GENRES.put("драм", "Драма"); GENRES.put("серьезн", "Драма"); GENRES.put("поплакать", "Драма");
        GENRES.put("мульт", "Мультфильм"); GENRES.put("аниме", "Мультфильм"); GENRES.put("анимац", "Мультфильм");
        GENRES.put("боевик", "Боевик"); GENRES.put("экшн", "Боевик"); GENRES.put("экшен", "Боевик"); GENRES.put("перестрел", "Боевик");
    }
    private static final String[][] DAYS = {
            {"понедельн", "MONDAY"}, {"вторник", "TUESDAY"}, {"сред", "WEDNESDAY"}, {"четверг", "THURSDAY"},
            {"пятниц", "FRIDAY"}, {"суббот", "SATURDAY"}, {"воскресен", "SUNDAY"}};

    private final Remote remote;
    private final SeatService seats;

    public AssistantService(Remote remote, SeatService seats) {
        this.remote = remote; this.seats = seats;
    }

    public AssistantReply ask(AssistantRequest req) {
        String t = " " + (req.text() == null ? "" : req.text()).toLowerCase(Locale.ROOT).replace('ё', 'е') + " ";
        List<String> chips = new ArrayList<>();

        // ---------- жанр ----------
        String genre = null;
        for (Map.Entry<String, String> e : GENRES.entrySet()) {
            if (t.contains(e.getKey())) { genre = e.getValue(); break; }
        }
        boolean kids = t.contains("с детьми") || t.contains("с ребенком") || t.contains("с сыном") || t.contains("с дочкой");
        if (kids && genre == null) genre = "Мультфильм";

        // ---------- день ----------
        LocalDate today = LocalDate.now();
        LocalDate dayFrom = today, dayTo = today.plusDays(2);
        String dayLabel = null;
        if (t.contains("послезавтра")) { dayFrom = dayTo = today.plusDays(2); dayLabel = "послезавтра"; }
        else if (t.contains("завтра")) { dayFrom = dayTo = today.plusDays(1); dayLabel = "завтра"; }
        else if (t.contains("сегодня")) { dayFrom = dayTo = today; dayLabel = "сегодня"; }
        else if (t.contains("выходн")) {
            dayFrom = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            dayTo = dayFrom.plusDays(1);
            dayLabel = "выходные";
        } else {
            for (String[] d : DAYS) {
                if (t.contains(d[0])) {
                    dayFrom = dayTo = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(d[1])));
                    dayLabel = dayFrom.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("ru"));
                    break;
                }
            }
        }
        if (dayLabel != null) chips.add(dayLabel);

        // ---------- время ----------
        int hFrom = 0, hTo = 28;
        String timeLabel = null;
        Matcher after = Pattern.compile("после (\\d{1,2})").matcher(t);
        Matcher before = Pattern.compile("до (\\d{1,2})").matcher(t);
        Matcher at = Pattern.compile(" в (\\d{1,2})(?::\\d\\d)?[ ,.]").matcher(t);
        if (after.find()) { hFrom = pm(Integer.parseInt(after.group(1)), t); timeLabel = "после " + hFrom + ":00"; }
        else if (t.contains("утр")) { hFrom = 6; hTo = 12; timeLabel = "утро"; }
        else if (t.contains("днем") || t.contains("обед")) { hFrom = 12; hTo = 17; timeLabel = "днём"; }
        else if (t.contains("ночь") || t.contains("ночн") || t.contains("поздн")) { hFrom = 21; timeLabel = "поздно вечером"; }
        else if (t.contains("вечер")) { hFrom = 17; timeLabel = "вечер"; }
        else if (at.find()) {
            int h = pm(Integer.parseInt(at.group(1)), t);
            hFrom = h - 1; hTo = h + 2; timeLabel = "около " + h + ":00";
        }
        if (before.find()) { hTo = pm(Integer.parseInt(before.group(1)), t); timeLabel = (timeLabel == null ? "" : timeLabel + ", ") + "до " + hTo + ":00"; }
        if (timeLabel != null) chips.add(timeLabel);

        // ---------- количество ----------
        int count = 0;
        Matcher num = Pattern.compile("(\\d{1,2})\\s*(мест|билет|человек|чел|друз)").matcher(t);
        if (num.find()) count = Integer.parseInt(num.group(1));
        else if (t.contains("вдвоем") || t.contains("двоих") || t.contains("с девушкой") || t.contains("с парнем")
                || t.contains("с другом") || t.contains("с подругой") || t.contains("с женой") || t.contains("с мужем")
                || t.contains("на свидание") || t.contains(" два ") || t.contains(" две ")) count = 2;
        else if (t.contains("втроем") || t.contains("троих") || t.contains(" три ")) count = 3;
        else if (t.contains("вчетвером") || t.contains("четверых") || t.contains(" четыре ")) count = 4;
        else if (t.contains("впятером") || t.contains("пятерых")) count = 5;
        else if (t.contains("компани")) count = 4;
        else if (kids) count = 3;
        if (count <= 0) count = 1;
        count = Math.min(count, 8);
        chips.add(count + " " + plural(count, "место", "места", "мест"));

        boolean cheap = t.contains("недорог") || t.contains("дешев") || t.contains("бюджет") || t.contains("эконом")
                || t.contains("подешевле");
        if (cheap) chips.add("недорого");
        boolean imax = t.contains("imax") || t.contains("аймакс") || t.contains("большой экран");
        if (imax) chips.add("IMAX");
        boolean back = t.contains("сзади") || t.contains("последн") || t.contains("на задн");
        if (genre != null) chips.add(0, genre.toLowerCase(Locale.ROOT));

        // ---------- поиск ----------
        LocalDateTime from = dayFrom.equals(today) ? LocalDateTime.now().plusMinutes(10) : dayFrom.atStartOfDay();
        LocalDateTime to = dayTo.plusDays(1).atStartOfDay().plusHours(4);
        List<SessionInfo> all = remote.sessions(req.cityId(), from, to);

        String movie = null;
        for (SessionInfo s : all) {
            for (String w : s.movieTitle().toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^а-яa-z0-9]+")) {
                if (w.length() >= 4 && t.contains(w.substring(0, Math.min(5, w.length())))) { movie = s.movieTitle(); break; }
            }
            if (movie != null) break;
        }
        if (movie != null) { chips.add(0, "«" + movie + "»"); genre = null; }

        final String g = genre, mv = movie;
        final int hf = hFrom, ht = hTo;
        List<SessionInfo> candidates = all.stream()
                .filter(s -> g == null || g.equals(s.genre()))
                .filter(s -> mv == null || mv.equals(s.movieTitle()))
                .filter(s -> !imax || "IMAX".equalsIgnoreCase(s.hallType()))
                .filter(s -> {
                    int h = s.startTime().getHour();
                    if (h < 4) h += 24;
                    return h >= hf && h < ht;
                })
                .sorted(Comparator.comparing(SessionInfo::startTime))
                .limit(25).toList();

        List<Suggestion> found = new ArrayList<>();
        for (SessionInfo s : candidates) {
            List<Seat> run = findRun(s, count, back);
            if (run == null) continue;
            Stats st = seats.stats(s.id());
            PriceQuote q = remote.quote(new PriceQuoteRequest(new SessionPricing(s.startTime(), s.basePrice(),
                    s.rows(), s.seatsPerRow(), (st.sold() + st.held()) / (double) st.total()), run, null));
            found.add(new Suggestion(s, run, q.total(), why(s, run, q, st)));
            if (found.size() >= 12) break;
        }
        if (cheap) found.sort(Comparator.comparingInt(Suggestion::total));
        List<Suggestion> top = found.stream().limit(3).toList();

        Understanding u = new Understanding(genre, movie, dayLabel, timeLabel, count, cheap, imax, chips);
        String reply;
        if (top.isEmpty()) {
            reply = "Не нашёл подходящих сеансов с " + count + " " + plural(count, "свободным местом", "свободными местами", "свободными местами")
                    + " рядом. Попробуйте другой день, время или жанр.";
        } else {
            Suggestion best = top.get(0);
            reply = "Лучший вариант — «" + best.session().movieTitle() + "» в "
                    + best.session().startTime().toLocalTime() + ", " + best.session().cinemaName()
                    + ". Нашёл " + count + " " + plural(count, "место", "места", "мест") + " рядом за " + best.total() + " ₽.";
        }
        return new AssistantReply(reply, u, top);
    }

    /** N свободных мест подряд, начиная с лучших рядов и ближе к центру. */
    private List<Seat> findRun(SessionInfo s, int n, boolean back) {
        Set<String> busy = new HashSet<>(seats.sold(s.id()));
        busy.addAll(seats.holds(s.id()).keySet());
        List<Integer> rows = new ArrayList<>();
        for (int r = 1; r <= s.rows(); r++) rows.add(r);
        double ideal = back ? s.rows() : s.rows() * 0.62;
        rows.sort(Comparator.comparingDouble(r -> Math.abs(r - ideal)));
        double center = (s.seatsPerRow() + 1) / 2.0;
        for (int r : rows) {
            List<Integer> starts = new ArrayList<>();
            for (int c = 1; c + n - 1 <= s.seatsPerRow(); c++) starts.add(c);
            starts.sort(Comparator.comparingDouble(c -> Math.abs(c + (n - 1) / 2.0 - center)));
            for (int c : starts) {
                boolean ok = true;
                for (int k = 0; k < n && ok; k++) if (busy.contains(r + "-" + (c + k))) ok = false;
                if (ok) {
                    List<Seat> out = new ArrayList<>();
                    for (int k = 0; k < n; k++) out.add(new Seat(r, c + k));
                    return out;
                }
            }
        }
        return null;
    }

    private static String why(SessionInfo s, List<Seat> run, PriceQuote q, Stats st) {
        Seat first = run.get(0), last = run.get(run.size() - 1);
        String place = run.size() == 1 ? "ряд " + first.row() + ", место " + first.seat()
                : "ряд " + first.row() + ", места " + first.seat() + "–" + last.seat();
        String factors = q.items().isEmpty() || q.items().get(0).factors().isEmpty() ? ""
                : " · " + String.join(", ", q.items().get(0).factors());
        return place + " · заполнено " + st.percent() + "%" + factors;
    }

    private static int pm(int h, String t) {
        if (h <= 11 && !t.contains("утр") && h >= 1 && h <= 11) return h + 12;
        return h;
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return few;
        return many;
    }
}
