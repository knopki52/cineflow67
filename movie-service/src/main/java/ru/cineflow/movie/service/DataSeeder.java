package ru.cineflow.movie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.cineflow.movie.model.*;
import ru.cineflow.movie.repo.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Наполняет каталог демо-данными при первом запуске и каждый день достраивает расписание
 * на неделю вперёд, чтобы демо всегда было «свежим».
 */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CityRepository cities;
    private final CinemaRepository cinemas;
    private final HallRepository halls;
    private final MovieRepository movies;
    private final SessionRepository sessions;
    private final ReviewRepository reviews;

    public DataSeeder(CityRepository cities, CinemaRepository cinemas, HallRepository halls,
                      MovieRepository movies, SessionRepository sessions, ReviewRepository reviews) {
        this.cities = cities; this.cinemas = cinemas; this.halls = halls;
        this.movies = movies; this.sessions = sessions; this.reviews = reviews;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (movies.count() == 0) seedCatalog();
        ensureSchedule();
    }

    private void seedCatalog() {
        log.info("Первый запуск: создаю города, кинотеатры, залы и фильмы");
        City msk = cities.save(new City("Москва", 55.7558, 37.6173));
        City spb = cities.save(new City("Санкт-Петербург", 59.9343, 30.3351));
        City kzn = cities.save(new City("Казань", 55.7963, 49.1088));

        List<Cinema> list = List.of(
                new Cinema(msk.id, "CineFlow Арбат", "ул. Новый Арбат, 24", 55.7522, 37.5927),
                new Cinema(msk.id, "CineFlow Сити", "Пресненская наб., 2", 55.7494, 37.5395),
                new Cinema(msk.id, "CineFlow ВДНХ", "пр. Мира, 119", 55.8263, 37.6377),
                new Cinema(spb.id, "CineFlow Невский", "Невский пр., 60", 59.9343, 30.3351),
                new Cinema(spb.id, "CineFlow Петроградка", "Каменноостровский пр., 42", 59.9612, 30.3111),
                new Cinema(kzn.id, "CineFlow Баумана", "ул. Баумана, 51", 55.7887, 49.1221));
        for (Cinema c : list) {
            Cinema saved = cinemas.save(c);
            halls.save(new Hall(saved.id, "Зал 1", "Стандарт", 8, 12, 0));
            halls.save(new Hall(saved.id, "Зал 2", "Комфорт", 7, 10, 100));
            halls.save(new Hall(saved.id, "Зал 3", "IMAX", 10, 14, 200));
        }

        movie("Дюна: Часть вторая", "dune", "Фантастика", 166, "12+", 2024, "Дени Вильнёв",
                "Пол Атрейдес объединяется с фрименами, чтобы отомстить заговорщикам, уничтожившим его семью. "
                        + "Пустыня, песчаные черви и выбор между любовью и судьбой целой вселенной.",
                "#C8873A", "Way9Dexny3w", 450);
        movie("Интерстеллар", "interstellar", "Фантастика", 169, "12+", 2014, "Кристофер Нолан",
                "Земля умирает, и группа исследователей отправляется через червоточину искать новый дом "
                        + "для человечества. Время там течёт иначе, а дома ждут дети.",
                "#2B4A6F", "zSWdZVtXT7E", 400);
        movie("Начало", "inception", "Триллер", 148, "12+", 2010, "Кристофер Нолан",
                "Профессиональный вор крадёт идеи из снов. Последнее задание обратное: не украсть мысль, "
                        + "а внедрить её. Сны внутри снов и волчок, который никак не остановится.",
                "#3A4E5C", "YoHD9XEInc0", 400);
        movie("Матрица", "matrix", "Фантастика", 136, "16+", 1999, "Лана и Лилли Вачовски",
                "Хакер Нео узнаёт, что привычный мир — это симуляция, а люди служат батарейками для машин. "
                        + "Красная таблетка или синяя?",
                "#1F6B3A", "vKQi3bBA1y8", 350);
        movie("Оно", "it", "Ужасы", 135, "18+", 2017, "Андрес Мускетти",
                "В городке Дерри пропадают дети. Компания подростков-неудачников выясняет, что за этим стоит "
                        + "древнее зло в облике клоуна.",
                "#8E1B1B", "FnCdOQsX5kc", 400);
        movie("Оппенгеймер", "oppenheimer", "Драма", 180, "18+", 2023, "Кристофер Нолан",
                "История физика, который возглавил создание атомной бомбы, и цены, которую ему пришлось "
                        + "заплатить за то, что он изменил мир навсегда.",
                "#B5532B", "uYPbbksJxIg", 450);
        movie("Унесённые призраками", "spirited", "Мультфильм", 125, "12+", 2001, "Хаяо Миядзаки",
                "Десятилетняя Тихиро попадает в мир духов и устраивается работать в волшебную баню, "
                        + "чтобы спасти родителей, превращённых в свиней.",
                "#3E8E7E", "ByXuk9QqQkk", 350);
        movie("Мальчишник в Вегасе", "hangover", "Комедия", 100, "18+", 2009, "Тодд Филлипс",
                "Трое друзей просыпаются после мальчишника в номере с тигром, но без жениха. "
                        + "До свадьбы меньше суток, а вспомнить прошлую ночь не может никто.",
                "#D4A017", "tlize92ffnY", 350);
        movie("Джокер", "joker", "Триллер", 122, "18+", 2019, "Тодд Филлипс",
                "Неудачливый комик Артур Флек живёт в равнодушном городе, который шаг за шагом "
                        + "толкает его к безумию.",
                "#6B2D5C", "zAGVQLHvwOY", 400);
        movie("Барби", "barbie", "Комедия", 114, "16+", 2023, "Грета Гервиг",
                "Идеальная жизнь в Барбиленде даёт трещину, и Барби отправляется в реальный мир, "
                        + "чтобы понять, что с ней происходит. Кен, конечно, едет следом.",
                "#E25C9A", "pBk4NYhWNMM", 400);
        movie("Джон Уик 4", "johnwick", "Боевик", 169, "18+", 2023, "Чад Стахелски",
                "Джон Уик ищет способ победить Правление Кланов. Чтобы обрести свободу, ему придётся "
                        + "пройти через Париж, Осаку и Берлин.",
                "#2D3142", null, 450);
        movie("Тихое место", "quietplace", "Ужасы", 90, "16+", 2018, "Джон Красински",
                "Семья выживает в мире, где чудовища охотятся на любой звук. Нельзя шуметь. Совсем.",
                "#4A5240", null, 350);

        String[] names = {"Аня", "Максим", "Лиза", "Артём", "Соня", "Денис", "Вика", "Илья"};
        String[] texts = {"Пересматривал бы снова, звук в зале просто огонь", "Сильно, но местами затянуто",
                "Лучший фильм года, без вариантов", "Пошли компанией, все вышли под впечатлением",
                "Визуально шикарно, сюжет на любителя", "Мурашки с первой минуты"};
        int i = 0;
        for (Movie m : movies.findAll()) {
            for (int k = 0; k < 3 + (i % 3); k++) {
                Review r = new Review();
                r.movieId = m.id;
                r.userId = 0L;
                r.userName = names[(i + k) % names.length];
                r.rating = 3 + ((i * 7 + k * 3) % 3);
                r.text = texts[(i + k) % texts.length];
                r.createdAt = LocalDateTime.now().minusDays(k + 1L);
                reviews.save(r);
            }
            i++;
        }
    }

    private void movie(String title, String slug, String genre, int duration, String age, int year, String director,
                       String description, String color, String trailer, int price) {
        Movie m = new Movie();
        m.title = title; m.slug = slug; m.genre = genre; m.durationMin = duration; m.ageRating = age;
        m.year = year; m.director = director; m.description = description;
        m.posterUrl = "/posters/" + slug + ".svg";
        m.color = color; m.trailerYoutubeId = trailer; m.basePrice = price;
        movies.save(m);
    }

    /** Каждую ночь достраиваем расписание на 7 дней вперёд. */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void ensureSchedule() {
        List<Movie> ms = movies.findAll();
        List<Cinema> cs = cinemas.findAll();
        if (ms.isEmpty()) return;
        int created = 0;
        for (int day = 0; day < 7; day++) {
            LocalDate date = LocalDate.now().plusDays(day);
            if (sessions.existsByStartTimeBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay())) continue;
            for (int ci = 0; ci < cs.size(); ci++) {
                Cinema c = cs.get(ci);
                List<Hall> hs = halls.findByCinemaId(c.id);
                for (int hi = 0; hi < hs.size(); hi++) {
                    Hall h = hs.get(hi);
                    for (int slot = 0; slot < SLOTS[hi % SLOTS.length].length; slot++) {
                        Movie m = ms.get(Math.floorMod(ci * 7 + hi * 3 + date.getDayOfYear() * 5 + slot, ms.size()));
                        LocalTime t = SLOTS[hi % SLOTS.length][slot];
                        sessions.save(session(m, h, c, date.atTime(t)));
                        created++;
                    }
                }
            }
        }
        if (created > 0) log.info("Расписание дополнено: {} сеансов", created);
    }

    private static final LocalTime[][] SLOTS = {
            {LocalTime.of(10, 0), LocalTime.of(13, 20), LocalTime.of(16, 40), LocalTime.of(19, 30), LocalTime.of(22, 20)},
            {LocalTime.of(11, 15), LocalTime.of(14, 30), LocalTime.of(17, 50), LocalTime.of(21, 0)},
            {LocalTime.of(12, 0), LocalTime.of(15, 40), LocalTime.of(19, 0), LocalTime.of(22, 30)}};

    private MovieSession session(Movie m, Hall h, Cinema c, LocalDateTime start) {
        MovieSession s = new MovieSession();
        s.movieId = m.id; s.hallId = h.id; s.cinemaId = c.id; s.cityId = c.cityId;
        s.startTime = start; s.basePrice = m.basePrice + h.priceExtra;
        return s;
    }

    /** Для демонстрации напоминания в Telegram: сеанс, который начнётся чуть больше чем через час. */
    @Transactional
    public MovieSession createSoonSession() {
        Cinema c = cinemas.findAll().get(0);
        Hall h = halls.findByCinemaId(c.id).get(0);
        Movie m = movies.findAll().get(0);
        LocalDateTime start = LocalDateTime.now().plusMinutes(62).withSecond(0).withNano(0);
        log.info("Создан демо-сеанс через 62 минуты: {} в {}", m.title, start);
        return sessions.save(session(m, h, c, start));
    }
}
