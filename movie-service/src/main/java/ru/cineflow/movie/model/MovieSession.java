package ru.cineflow.movie.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "sessions", indexes = {
        @Index(columnList = "startTime"), @Index(columnList = "movieId"), @Index(columnList = "cityId")})
public class MovieSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long movieId;
    public Long hallId;
    public Long cinemaId;
    public Long cityId;
    public LocalDateTime startTime;
    public int basePrice;
}
