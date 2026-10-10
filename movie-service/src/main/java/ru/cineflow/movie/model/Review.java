package ru.cineflow.movie.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
public class Review {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long movieId;
    public Long userId;
    public String userName;
    public int rating;
    @Column(length = 2000)
    public String text;
    public LocalDateTime createdAt = LocalDateTime.now();
}
