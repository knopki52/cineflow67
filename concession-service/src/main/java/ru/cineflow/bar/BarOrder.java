package ru.cineflow.bar;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bar_orders")
public class BarOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** номер заказа в booking-service */
    public Long bookingOrderId;
    public Long userId;
    public Long sessionId;
    public String movieTitle;
    public String cinemaName;
    public String hallName;
    public String seatLabel;
    public LocalDateTime startTime;
    @Column(length = 2000)
    public String itemsText;
    public int itemsCount;
    public int popcornCount;
    public int total;
    /** NEW, COOKING, DELIVERING, DELIVERED, CANCELLED */
    public String status = "NEW";
    public LocalDateTime createdAt = LocalDateTime.now();
}
