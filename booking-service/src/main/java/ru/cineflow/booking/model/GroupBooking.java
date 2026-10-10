package ru.cineflow.booking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "group_bookings")
public class GroupBooking {
    @Id
    public String id;
    public Long sessionId;
    public Long organizerId;
    public String organizerName;
    /** места через запятую: "5-7,5-8,5-9" */
    public String seats;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime expiresAt;
}
