package ru.cineflow.movie.dto;

import java.util.List;

public record Recommendations(String title, String reason, List<MovieView> movies) {}
