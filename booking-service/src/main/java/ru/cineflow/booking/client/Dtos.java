package ru.cineflow.booking.client;

import java.time.LocalDateTime;
import java.util.List;

/** Контракты других микросервисов (копии их DTO — сервисы не делят общий код). */
public final class Dtos {
    private Dtos() {}

    public record SessionInfo(
            Long id, Long movieId, String movieTitle, String genre, int durationMin, String posterUrl,
            String color, String ageRating,
            Long hallId, String hallName, String hallType, int rows, int seatsPerRow,
            Long cinemaId, String cinemaName, String cinemaAddress, double lat, double lng,
            Long cityId, String cityName,
            LocalDateTime startTime, int basePrice) {}

    public record Seat(int row, int seat) {}

    public record SessionPricing(LocalDateTime startTime, int basePrice, int rows, int seatsPerRow, double occupancy) {}
    public record PriceQuoteRequest(SessionPricing session, List<Seat> seats, String promoCode) {}
    public record SeatPrice(int row, int seat, int price, List<String> factors) {}
    public record PriceQuote(List<SeatPrice> items, int subtotal, int discount, int total,
                             String promoCode, boolean promoApplied, String promoMessage) {}
    public record SeatMapPrices(int[][] prices, int min, int max, List<String> sessionFactors) {}

    public record PaymentRequest(String orderRef, Long userId, int amount, String description) {}
    public record PaymentResult(String paymentId, String status, int amount, LocalDateTime paidAt) {}
    public record RefundRequest(String paymentId, int amount, String reason) {}

    public record BarLine(Long itemId, int qty) {}
    public record BarQuoteRequest(List<BarLine> items, boolean freePopcorn) {}
    public record BarQuoteLine(Long itemId, String name, String emoji, int qty, int price, int sum) {}
    public record BarQuote(List<BarQuoteLine> lines, int total, int discount, int itemsCount, int popcornCount, String note) {}
    public record BarCreate(Long bookingOrderId, Long userId, Long sessionId, String movieTitle, String cinemaName,
                            String hallName, String seatLabel, LocalDateTime startTime,
                            List<BarLine> items, boolean freePopcorn) {}
    public record BarOrderView(Long id, String status, int total, String itemsText) {}

    public record UserView(Long id, String name, String email, String role, int points, int totalSpent,
                           String level, int percent, boolean telegramLinked) {}
    public record PointsRequest(Long orderId, int points) {}
}
