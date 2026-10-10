package ru.cineflow.movie.dto;

import java.time.LocalDateTime;

/** Полная информация о сеансе: её используют booking-service, бот и сайт. */
public record SessionInfo(
        Long id, Long movieId, String movieTitle, String genre, int durationMin, String posterUrl,
        String color, String ageRating,
        Long hallId, String hallName, String hallType, int rows, int seatsPerRow,
        Long cinemaId, String cinemaName, String cinemaAddress, double lat, double lng,
        Long cityId, String cityName,
        LocalDateTime startTime, int basePrice) {}
