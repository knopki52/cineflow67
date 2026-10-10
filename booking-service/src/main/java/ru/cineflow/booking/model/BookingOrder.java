package ru.cineflow.booking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {@Index(columnList = "userId"), @Index(columnList = "createdAt")})
public class BookingOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String userName;
    public Long sessionId;
    public String groupId;
    public String movieTitle;
    public String cinemaName;
    public String hallName;
    public LocalDateTime startTime;
    /** сумма билетов до скидок */
    public int ticketsAmount;
    public int discount;
    public String promoCode;
    public int pointsUsed;
    public int barAmount;
    public int paidAmount;
    public String paymentId;
    public Long barOrderId;
    public int barItemsCount;
    public int popcornCount;
    /** NEW, PAID, FAILED, PARTIALLY_REFUNDED, REFUNDED */
    public String status = "NEW";
    public LocalDateTime createdAt = LocalDateTime.now();
}
