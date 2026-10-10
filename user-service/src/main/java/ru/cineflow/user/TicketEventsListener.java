package ru.cineflow.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Бонусы начисляются по событию из Kafka, а не прямым вызовом:
 * booking-service ничего не знает о программе лояльности.
 */
@Component
public class TicketEventsListener {
    private static final Logger log = LoggerFactory.getLogger(TicketEventsListener.class);
    private final UserService users;
    private final ObjectMapper json;

    public TicketEventsListener(UserService users, ObjectMapper json) {
        this.users = users; this.json = json;
    }

    @KafkaListener(topics = "ticket-events", groupId = "user-service")
    public void onEvent(String message) {
        try {
            JsonNode e = json.readTree(message);
            String type = e.path("type").asText();
            long userId = e.path("userId").asLong();
            long orderId = e.path("orderId").asLong();
            switch (type) {
                case "ORDER_PAID" -> users.onOrderPaid(userId, orderId, e.path("paidAmount").asInt());
                case "TICKETS_REFUNDED" -> users.onOrderRefunded(userId, orderId,
                        e.path("refundAmount").asInt(), e.path("share").asDouble(1.0));
                default -> { }
            }
        } catch (Exception ex) {
            log.error("Ошибка обработки события {}: {}", message, ex.toString());
        }
    }
}
