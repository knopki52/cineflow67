package ru.cineflow.booking.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class LiveEvents implements MessageListener {
    public static final String CHANNEL = "cineflow-live";
    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate stomp;
    private final ObjectMapper json;

    public LiveEvents(StringRedisTemplate redis, SimpMessagingTemplate stomp, ObjectMapper json) {
        this.redis = redis; this.stomp = stomp; this.json = json;
    }

    public void publish(String destination, Object payload) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("dest", destination, "payload", payload)));
        } catch (Exception e) {
            log.debug("Redis relay недоступен, отправляю напрямую: {}", e.toString());
            stomp.convertAndSend(destination, payload);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            stomp.convertAndSend(node.get("dest").asText(), node.get("payload"));
        } catch (Exception e) {
            log.warn("Не удалось разослать live-событие: {}", e.toString());
        }
    }

    public void seat(Long sessionId, int row, int seat, String state, String by) {
        publish("/topic/session/" + sessionId + "/seats",
                Map.of("row", row, "seat", seat, "state", state, "by", by == null ? "" : by));
    }
}
