package ru.cineflow.movie.dto;

import ru.cineflow.movie.model.Movie;

public record MovieView(Long id, String title, String slug, String genre, int durationMin, String ageRating,
                        int year, String director, String description, String posterUrl, String color,
                        String trailerYoutubeId, int basePrice, double rating, int reviewsCount) {

    public static MovieView of(Movie m, double rating, int reviews) {
        return new MovieView(m.id, m.title, m.slug, m.genre, m.durationMin, m.ageRating, m.year, m.director,
                m.description, m.posterUrl, m.color, m.trailerYoutubeId, m.basePrice, rating, reviews);
    }
}
