package ru.cineflow.booking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "waitlist")
public class WaitlistEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long sessionId;
    public Long userId;
    public String userName;
    /** WAITING, OFFERED, DONE, EXPIRED, CANCELLED */
    public String status = "WAITING";
    public Integer offeredRow;
    public Integer offeredSeat;
    public LocalDateTime offerExpiresAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
