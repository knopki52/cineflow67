package ru.cineflow.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Публикация событий в Kafka. Сервисы-подписчики сами решают, что с ними делать. */
@Component
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    public static final String TICKET_EVENTS = "ticket-events";
    public static final String NOTIFICATIONS = "notifications";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka; this.json = json;
    }

    public void ticketEvent(Map<String, Object> event) { send(TICKET_EVENTS, event); }

    public void notify(Map<String, Object> event) { send(NOTIFICATIONS, event); }

    private void send(String topic, Map<String, Object> event) {
        try {
            kafka.send(topic, String.valueOf(event.get("userId")), json.writeValueAsString(event));
            log.info("→ Kafka [{}] {}", topic, event.get("type"));
        } catch (Exception e) {
            log.warn("Kafka недоступна, событие {} не отправлено: {}", event.get("type"), e.toString());
        }
    }
}
