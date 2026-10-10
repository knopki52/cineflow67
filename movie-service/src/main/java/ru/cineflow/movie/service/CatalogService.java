package ru.cineflow.movie.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.cineflow.movie.client.BookingGateway;
import ru.cineflow.movie.dto.MovieView;
import ru.cineflow.movie.dto.Recommendations;
import ru.cineflow.movie.dto.SessionInfo;
import ru.cineflow.movie.model.*;
import ru.cineflow.movie.repo.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogService {
    private final CityRepository cities;
    private final CinemaRepository cinemas;
    private final HallRepository halls;
    private final MovieRepository movies;
    private final SessionRepository sessions;
    private final ReviewRepository reviews;
    private final BookingGateway booking;

    public CatalogService(CityRepository cities, CinemaRepository cinemas, HallRepository halls,
                          MovieRepository movies, SessionRepository sessions, ReviewRepository reviews,
                          BookingGateway booking) {
        this.cities = cities; this.cinemas = cinemas; this.halls = halls; this.movies = movies;
        this.sessions = sessions; this.reviews = reviews; this.booking = booking;
    }

    public List<City> cities() { return cities.findAll(); }

    public List<Cinema> cinemas(Long cityId) {
        return cityId == null ? cinemas.findAll() : cinemas.findByCityId(cityId);
    }

    /** Фильмы, у которых в городе есть ближайшие сеансы. */
    public List<MovieView> moviesInCity(Long cityId) {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> ids = sessions.search(now, now.plusDays(7), cityId, null, null).stream()
                .map(s -> s.movieId).collect(Collectors.toSet());
        return movies.findAll().stream()
                .filter(m -> cityId == null || ids.contains(m.id))
                .map(this::view)
                .sorted(Comparator.comparingDouble(MovieView::rating).reversed())
                .toList();
    }

    public MovieView movie(Long id) {
        return view(movies.findById(id).orElseThrow(() -> notFound("Фильм не найден")));
    }

    public MovieView view(Movie m) {
        List<Review> list = reviews.findByMovieIdOrderByCreatedAtDesc(m.id);
        double avg = list.stream().mapToInt(r -> r.rating).average().orElse(0);
        return MovieView.of(m, Math.round(avg * 10) / 10.0, list.size());
    }

    public List<SessionInfo> search(Long cityId, Long cinemaId, Long movieId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime f = from != null ? from : LocalDateTime.now();
        LocalDateTime t = to != null ? to : f.plusDays(7);
        List<MovieSession> list = sessions.search(f, t, cityId, cinemaId, movieId);
        return toInfo(list.size() > 600 ? list.subList(0, 600) : list);
    }

    public List<SessionInfo> movieSessions(Long movieId, Long cityId, LocalDate date) {
        LocalDateTime from = date == null || date.equals(LocalDate.now())
                ? LocalDateTime.now() : date.atStartOfDay();
        LocalDateTime to = (date == null ? LocalDate.now() : date).plusDays(1).atStartOfDay().plusHours(3);
        return toInfo(sessions.search(from, to, cityId, null, movieId));
    }

    public SessionInfo session(Long id) {
        return toInfo(List.of(sessions.findById(id).orElseThrow(() -> notFound("Сеанс не найден")))).get(0);
    }

    public List<SessionInfo> toInfo(List<MovieSession> list) {
        Map<Long, Movie> mv = movies.findAll().stream().collect(Collectors.toMap(m -> m.id, Function.identity()));
        Map<Long, Hall> hl = halls.findAll().stream().collect(Collectors.toMap(h -> h.id, Function.identity()));
        Map<Long, Cinema> cn = cinemas.findAll().stream().collect(Collectors.toMap(c -> c.id, Function.identity()));
        Map<Long, City> ct = cities.findAll().stream().collect(Collectors.toMap(c -> c.id, Function.identity()));
        List<SessionInfo> out = new ArrayList<>();
        for (MovieSession s : list) {
            Movie m = mv.get(s.movieId);
            Hall h = hl.get(s.hallId);
            Cinema c = cn.get(s.cinemaId);
            City city = ct.get(s.cityId);
            if (m == null || h == null || c == null || city == null) continue;
            out.add(new SessionInfo(s.id, m.id, m.title, m.genre, m.durationMin, m.posterUrl, m.color, m.ageRating,
                    h.id, h.name, h.type, h.rows, h.seatsPerRow,
                    c.id, c.name, c.address, c.lat, c.lng, city.id, city.name,
                    s.startTime, s.basePrice));
        }
        return out;
    }

    /** Рекомендации без ИИ: жанры из истории покупок, иначе — топ продаж недели. */
    public Recommendations recommendations(Long userId, Long cityId) {
        List<MovieView> available = moviesInCity(cityId);
        if (userId != null) {
            List<Long> history = booking.userMovieIds(userId);
            if (!history.isEmpty()) {
                Map<Long, Movie> all = movies.findAll().stream().collect(Collectors.toMap(m -> m.id, Function.identity()));
                Map<String, Long> genreCount = history.stream().map(all::get).filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(m -> m.genre, Collectors.counting()));
                Set<Long> seen = new HashSet<>(history);
                List<MovieView> recs = available.stream()
                        .filter(m -> genreCount.containsKey(m.genre()) && !seen.contains(m.id()))
                        .sorted(Comparator.comparingLong((MovieView m) -> -genreCount.get(m.genre()))
                                .thenComparing(Comparator.comparingDouble(MovieView::rating).reversed()))
                        .limit(6).toList();
                if (!recs.isEmpty()) {
                    String favorite = genreCount.entrySet().stream()
                            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
                    Movie last = all.get(history.get(history.size() - 1));
                    String reason = last != null
                            ? "Потому что вы смотрели «" + last.title + "» и любите жанр «" + favorite.toLowerCase() + "»"
                            : "По вашим любимым жанрам";
                    return new Recommendations("Вам может понравиться", reason, recs);
                }
            }
        }
        List<Long> top = booking.topMovies(7);
        if (!top.isEmpty()) {
            Map<Long, MovieView> byId = available.stream().collect(Collectors.toMap(MovieView::id, Function.identity()));
            List<MovieView> list = top.stream().map(byId::get).filter(Objects::nonNull).limit(6).toList();
            if (!list.isEmpty()) return new Recommendations("Топ продаж недели", "Больше всего билетов за 7 дней", list);
        }
        return new Recommendations("Зрители выбирают", "Лучший рейтинг в вашем городе",
                available.stream().limit(6).toList());
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
