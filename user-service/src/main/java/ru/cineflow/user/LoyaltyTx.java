package ru.cineflow.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Журнал операций с баллами — нужен, чтобы при возврате билета списать ровно начисленное. */
@Entity
@Table(name = "loyalty_tx")
public class LoyaltyTx {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public Long orderId;
    /** EARN, SPEND, RETURN, REVOKE */
    public String type;
    public int points;
    public LocalDateTime createdAt = LocalDateTime.now();

    public LoyaltyTx() {}
    public LoyaltyTx(Long userId, Long orderId, String type, int points) {
        this.userId = userId; this.orderId = orderId; this.type = type; this.points = points;
    }
}
