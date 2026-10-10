package ru.cineflow.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final SecureRandom RND = new SecureRandom();

    private final UserRepository users;
    private final LoyaltyTxRepository txs;
    private final LoyaltyProperties props;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public UserService(UserRepository users, LoyaltyTxRepository txs, LoyaltyProperties props,
                       KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.users = users; this.txs = txs; this.props = props; this.kafka = kafka; this.json = json;
    }

    // ---------- уровни ----------
    public String level(AppUser u) {
        if (u.totalSpent >= props.getGoldThreshold()) return "GOLD";
        if (u.totalSpent >= props.getSilverThreshold()) return "SILVER";
        return "BASE";
    }

    public int percent(String level) {
        return switch (level) {
            case "GOLD" -> props.getGoldPercent();
            case "SILVER" -> props.getSilverPercent();
            default -> props.getBasePercent();
        };
    }

    public UserView view(AppUser u) {
        String level = level(u);
        String next = null;
        int toNext = 0;
        double progress = 1.0;
        if ("BASE".equals(level)) {
            next = "SILVER";
            toNext = props.getSilverThreshold() - u.totalSpent;
            progress = u.totalSpent / (double) props.getSilverThreshold();
        } else if ("SILVER".equals(level)) {
            next = "GOLD";
            toNext = props.getGoldThreshold() - u.totalSpent;
            progress = (u.totalSpent - props.getSilverThreshold())
                    / (double) (props.getGoldThreshold() - props.getSilverThreshold());
        }
        return new UserView(u.id, u.name, u.email, u.role, u.points, u.totalSpent, level, percent(level),
                next, Math.max(0, toNext), Math.min(1.0, Math.max(0, progress)),
                u.telegramChatId != null, u.telegramUsername, u.telegramChatId);
    }

    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    // ---------- вход ----------
    /** Упрощённый вход по имени и почте. Роль определяется служебными адресами. */
    @Transactional
    public AppUser login(String name, String email) {
        if (email == null || email.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите почту");
        String mail = email.trim().toLowerCase(Locale.ROOT);
        AppUser u = users.findByEmailIgnoreCase(mail).orElseGet(() -> {
            AppUser n = new AppUser();
            n.email = mail;
            n.name = name == null || name.isBlank() ? mail.split("@")[0] : name.trim();
            n.role = roleFor(mail);
            n.points = 200; // приветственные баллы
            log.info("Новый зритель {} ({}), роль {}", n.name, mail, n.role);
            return users.save(n);
        });
        if (name != null && !name.isBlank()) u.name = name.trim();
        return u;
    }

    private static String roleFor(String mail) {
        if (mail.startsWith("admin@")) return "ADMIN";
        if (mail.startsWith("control@")) return "CONTROLLER";
        if (mail.startsWith("bar@")) return "BAR";
        return "VIEWER";
    }

    // ---------- Telegram ----------
    @Transactional
    public Map<String, String> createLinkCode(Long userId) {
        AppUser u = get(userId);
        u.linkCode = Long.toString(Math.abs(RND.nextLong()), 36).substring(0, 8);
        String link = "https://t.me/" + props.getBotUsername() + "?start=link_" + u.linkCode;
        return Map.of("code", u.linkCode, "link", link, "bot", props.getBotUsername());
    }

    @Transactional
    public AppUser linkTelegram(String code, Long chatId, String username) {
        AppUser u = users.findByLinkCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Код привязки устарел. Получите новый на сайте"));
        // если этот чат уже создал «телеграм-аккаунт», переносим с него баллы и освобождаем chatId
        users.findByTelegramChatId(chatId).filter(other -> !other.id.equals(u.id)).ifPresent(other -> {
            u.points += other.points;
            other.points = 0;
            other.telegramChatId = null;
            users.saveAndFlush(other);
        });
        u.telegramChatId = chatId;
        u.telegramUsername = username;
        u.linkCode = null;
        log.info("Telegram {} привязан к пользователю {}", chatId, u.id);
        return u;
    }

    /** Вход из бота: если чат не привязан к сайту — создаём аккаунт по chatId. */
    @Transactional
    public AppUser telegramAuth(Long chatId, String name, String username) {
        return users.findByTelegramChatId(chatId).orElseGet(() -> {
            AppUser n = new AppUser();
            n.telegramChatId = chatId;
            n.telegramUsername = username;
            n.name = name == null || name.isBlank() ? "Зритель" : name;
            n.points = 200;
            log.info("Новый зритель из Telegram: {} ({})", n.name, chatId);
            return users.save(n);
        });
    }

    // ---------- баллы ----------
    @Transactional
    public AppUser spendPoints(Long userId, Long orderId, int points) {
        AppUser u = get(userId);
        if (points <= 0) return u;
        if (u.points < points) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недостаточно баллов: доступно " + u.points);
        }
        u.points -= points;
        txs.save(new LoyaltyTx(userId, orderId, "SPEND", points));
        log.info("Списано {} баллов у пользователя {}", points, userId);
        return u;
    }

    @Transactional
    public AppUser returnPoints(Long userId, Long orderId, int points) {
        AppUser u = get(userId);
        if (points > 0) {
            u.points += points;
            txs.save(new LoyaltyTx(userId, orderId, "RETURN", points));
            log.info("Компенсация: возвращено {} баллов пользователю {}", points, userId);
        }
        return u;
    }

    /** Событие ORDER_PAID из Kafka: начисляем баллы по текущему уровню и проверяем повышение уровня. */
    @Transactional
    public void onOrderPaid(Long userId, Long orderId, int paidAmount) {
        AppUser u = users.findById(userId).orElse(null);
        if (u == null) return; // демо-боты и сгенерированные зрители
        if (!txs.findByOrderIdAndType(orderId, "EARN").isEmpty()) return; // идемпотентность
        String before = level(u);
        int earned = (int) Math.round(paidAmount * percent(before) / 100.0);
        u.points += earned;
        u.totalSpent += paidAmount;
        txs.save(new LoyaltyTx(userId, orderId, "EARN", earned));
        String after = level(u);
        log.info("Начислено {} баллов пользователю {} за заказ {} (уровень {})", earned, userId, orderId, after);
        if (!after.equals(before)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "LEVEL_UP");
            event.put("userId", userId);
            event.put("level", after);
            event.put("percent", percent(after));
            event.put("freePopcorn", "GOLD".equals(after));
            send(event);
        }
    }

    @Transactional
    public void onOrderRefunded(Long userId, Long orderId, int refundAmount, double share) {
        AppUser u = users.findById(userId).orElse(null);
        if (u == null) return;
        int earned = txs.findByOrderIdAndType(orderId, "EARN").stream().mapToInt(t -> t.points).sum();
        int revoke = (int) Math.round(earned * share);
        u.points = Math.max(0, u.points - revoke);
        u.totalSpent = Math.max(0, u.totalSpent - refundAmount);
        txs.save(new LoyaltyTx(userId, orderId, "REVOKE", revoke));
        log.info("Возврат по заказу {}: списано {} начисленных баллов", orderId, revoke);
    }

    private void send(Map<String, Object> event) {
        try {
            kafka.send("notifications", String.valueOf(event.get("userId")), json.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Не удалось отправить событие: {}", e.toString());
        }
    }
}
