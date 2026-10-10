package ru.cineflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Единая точка доставки уведомлений.
 * Источники: Kafka-топики ticket-events (покупки, возвраты, проход) и notifications (напоминания,
 * лист ожидания, бар, уровни). Каналы: почта и Telegram (через топик telegram, его читает Python-бот).
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM, HH:mm", new Locale("ru"));

    public record Delivered(LocalDateTime at, String type, Long userId, boolean email, boolean telegram) {}

    private final UserLookup users;
    private final JavaMailSender mail;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Deque<Delivered> recent = new ArrayDeque<>();

    @Value("${notification.from:tickets@cineflow.ru}")
    private String from;

    public NotificationService(UserLookup users, JavaMailSender mail, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.users = users; this.mail = mail; this.kafka = kafka; this.json = json;
    }

    @KafkaListener(topics = "ticket-events", groupId = "notification-service")
    public void onTicketEvent(String message) { handle(message); }

    @KafkaListener(topics = "notifications", groupId = "notification-service")
    public void onNotification(String message) { handle(message); }

    public synchronized List<Delivered> recent() { return new ArrayList<>(recent); }

    private void handle(String message) {
        try {
            JsonNode e = json.readTree(message);
            if (e.path("demo").asBoolean(false)) return;
            String type = e.path("type").asText();
            if (!List.of("ORDER_PAID", "TICKETS_REFUNDED", "TICKET_USED", "REMINDER", "WAITLIST_OFFER",
                    "BAR_STATUS", "LEVEL_UP").contains(type)) return;
            long userId = e.path("userId").asLong();
            UserClient.UserView user = users.find(userId);
            if (user == null) return;

            boolean sentTg = false, sentMail = false;
            if (user.telegramChatId() != null) {
                ObjectNode tg = ((ObjectNode) e.deepCopy());
                tg.put("chatId", user.telegramChatId());
                tg.put("userName", user.name());
                tg.put("level", user.level());
                tg.put("points", user.points());
                kafka.send("telegram", String.valueOf(user.telegramChatId()), json.writeValueAsString(tg));
                sentTg = true;
            }
            if (user.email() != null && !user.email().isBlank() && wantsEmail(type)) {
                sendEmail(user, type, e);
                sentMail = true;
            }
            log.info("Уведомление {} для пользователя {}: почта={}, telegram={}", type, userId, sentMail, sentTg);
            synchronized (this) {
                recent.addFirst(new Delivered(LocalDateTime.now(), type, userId, sentMail, sentTg));
                while (recent.size() > 50) recent.removeLast();
            }
        } catch (Exception ex) {
            log.error("Ошибка обработки уведомления: {}", ex.toString());
        }
    }

    private static boolean wantsEmail(String type) {
        return List.of("ORDER_PAID", "TICKETS_REFUNDED", "REMINDER", "WAITLIST_OFFER").contains(type);
    }

    private void sendEmail(UserClient.UserView user, String type, JsonNode e) throws Exception {
        MimeMessage msg = mail.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setFrom(from);
        h.setTo(user.email());
        String title = e.path("movieTitle").asText("");
        switch (type) {
            case "ORDER_PAID" -> {
                h.setSubject("🎟 Ваши билеты на «" + title + "»");
                StringBuilder tickets = new StringBuilder();
                List<String> ids = new ArrayList<>();
                for (JsonNode t : e.path("tickets")) {
                    String id = t.path("id").asText();
                    ids.add(id);
                    tickets.append("""
                            <tr><td style="padding:16px;border-top:1px dashed #6b3a5e">
                              <div style="font-size:18px;font-weight:700">Ряд %d · Место %d</div>
                              <div style="opacity:.7;font-size:13px">%d ₽ · билет %s</div>
                            </td><td style="padding:16px;border-top:1px dashed #6b3a5e;text-align:right">
                              <img src="cid:qr-%s" width="130" height="130" style="border-radius:8px"/>
                            </td></tr>
                            """.formatted(t.path("row").asInt(), t.path("seat").asInt(), t.path("price").asInt(),
                            id.substring(0, 8), id));
                }
                String bar = e.path("barItems").isNull() || e.path("barItems").asText().isBlank() ? ""
                        : "<p style=\"margin:16px 0 0\">🍿 К вашему месту принесут: " + e.path("barItems").asText() + "</p>";
                h.setText(layout(user.name(), "Билеты оплачены",
                        "<p style=\"font-size:22px;font-weight:800;margin:0\">" + title + "</p>"
                                + "<p style=\"margin:6px 0 0;opacity:.8\">" + e.path("cinemaName").asText() + ", "
                                + e.path("hallName").asText() + " · " + when(e.path("startTime").asText()) + "</p>"
                                + "<p style=\"margin:4px 0 0;opacity:.6;font-size:13px\">" + e.path("cinemaAddress").asText() + "</p>"
                                + "<table style=\"width:100%;margin-top:16px;border-collapse:collapse\">" + tickets + "</table>"
                                + bar
                                + "<p style=\"margin-top:20px\">Оплачено: <b>" + e.path("paidAmount").asInt() + " ₽</b>"
                                + (e.path("pointsUsed").asInt() > 0 ? " · списано баллов: " + e.path("pointsUsed").asInt() : "")
                                + "</p><p style=\"opacity:.7;font-size:13px\">Покажите QR-код контролёру на входе в зал.</p>"), true);
                for (String id : ids) h.addInline("qr-" + id, new ByteArrayResource(QrCodes.png(id)), "image/png");
            }
            case "TICKETS_REFUNDED" -> {
                h.setSubject("↩️ Возврат билета на «" + title + "»");
                h.setText(layout(user.name(), "Билет возвращён",
                        "<p>Ряд " + e.path("row").asInt() + ", место " + e.path("seat").asInt() + " на «" + title + "».</p>"
                                + "<p>Вернём <b>" + e.path("refundAmount").asInt() + " ₽</b> (" + e.path("refundPercent").asInt()
                                + "% стоимости) в течение нескольких минут.</p>"), true);
            }
            case "REMINDER" -> {
                h.setSubject("⏰ Через час — «" + title + "»");
                h.setText(layout(user.name(), "Скоро начало",
                        "<p>Сеанс «" + title + "» начнётся в " + when(e.path("startTime").asText()) + ".</p><p>"
                                + e.path("cinemaName").asText() + ", " + e.path("hallName").asText() + ", ряд "
                                + e.path("row").asInt() + ", место " + e.path("seat").asInt() + ".</p>"), true);
            }
            case "WAITLIST_OFFER" -> {
                h.setSubject("🎉 Освободилось место на «" + title + "»");
                h.setText(layout(user.name(), "Место ваше на " + e.path("holdMinutes").asInt() + " минут",
                        "<p>Ряд " + e.path("row").asInt() + ", место " + e.path("seat").asInt() + " на «" + title + "» "
                                + when(e.path("startTime").asText()) + " держится за вами.</p>"
                                + "<p><a href=\"http://localhost:8080/#/session/" + e.path("sessionId").asLong()
                                + "\" style=\"color:#E0B354\">Перейти к оплате</a></p>"), true);
            }
            default -> { return; }
        }
        mail.send(msg);
    }

    private static String when(String iso) {
        try { return LocalDateTime.parse(iso).format(WHEN); } catch (Exception e) { return iso; }
    }

    private static String layout(String name, String heading, String body) {
        return """
                <div style="background:#1E0E1A;padding:32px;font-family:Arial,sans-serif;color:#F4F0FF">
                  <div style="max-width:560px;margin:0 auto;background:#2E1528;border-radius:18px;padding:28px">
                    <div style="color:#E0B354;font-weight:800;letter-spacing:.5px">CineFlow</div>
                    <h1 style="margin:8px 0 4px;font-size:26px">%s</h1>
                    <p style="opacity:.7;margin:0 0 20px">%s, вот детали:</p>
                    %s
                  </div>
                </div>""".formatted(heading, name == null ? "Здравствуйте" : name, body);
    }
}
