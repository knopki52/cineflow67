package ru.cineflow.movie.model;

import jakarta.persistence.*;

@Entity
@Table(name = "movies")
public class Movie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String slug;
    public String genre;
    public int durationMin;
    public String ageRating;
    @Column(name = "release_year")
    public int year;
    public String director;
    @Column(length = 2000)
    public String description;
    public String posterUrl;
    /** запасной цвет страницы, если ColorThief не сможет прочитать постер */
    public String color;
    public String trailerYoutubeId;
    public int basePrice;
}
