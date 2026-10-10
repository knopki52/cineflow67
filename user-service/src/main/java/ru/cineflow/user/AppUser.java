package ru.cineflow.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    @Column(unique = true)
    public String email;
    /** VIEWER, ADMIN, CONTROLLER, BAR */
    public String role = "VIEWER";
    @Column(unique = true)
    public Long telegramChatId;
    public String telegramUsername;
    public String linkCode;
    public int points;
    public int totalSpent;
    public LocalDateTime createdAt = LocalDateTime.now();
}
