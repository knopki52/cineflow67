package ru.cineflow.movie.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewRequest(@NotNull Long userId, String userName,
                            @Min(1) @Max(5) int rating, String text) {}
