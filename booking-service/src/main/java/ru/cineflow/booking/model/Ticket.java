package ru.cineflow.booking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets", indexes = {
        @Index(columnList = "sessionId"), @Index(columnList = "userId"), @Index(columnList = "startTime")})
public class Ticket {
    /** UUID — его же кодируем в QR */
    @Id
    public String id;
    public Long orderId;
    public Long userId;
    public String buyerName;
    public Long sessionId;
    public String groupId;
    public Long movieId;
    public String movieTitle;
    public String genre;
    public int durationMin;
    public String cinemaName;
    public String cityName;
    public String hallName;
    @Column(name = "row_no")
    public int row;
    @Column(name = "seat_no")
    public int seat;
    public int price;
    /** PAID, USED, REFUNDED */
    public String status = "PAID";
    public int refundAmount;
    public LocalDateTime startTime;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime usedAt;
    public boolean reminded;
}
