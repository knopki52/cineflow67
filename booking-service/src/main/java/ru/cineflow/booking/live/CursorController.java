package ru.cineflow.booking.live;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * Курсоры зрителей как в Figma. Координаты передаются в «единицах зала»
 * (x — номер места с дробной частью, y — ряд), поэтому курсор указывает на то же кресло
 * на любом экране.
 */
@Controller
public class CursorController {

    public record CursorMessage(Long sessionId, String clientId, String name, String color,
                                double x, double y, Integer row, Integer seat, boolean touch, boolean leave) {}

    private final LiveEvents live;

    public CursorController(LiveEvents live) { this.live = live; }

    @MessageMapping("/cursor")
    public void cursor(CursorMessage m) {
        if (m.sessionId() == null) return;
        live.publish("/topic/session/" + m.sessionId() + "/cursors", m);
    }
}
