package ru.cineflow.booking.service;

import ru.cineflow.booking.client.Dtos;
import ru.cineflow.booking.client.Dtos.SessionInfo;

import java.time.LocalDateTime;
import java.util.List;

/** Ответы REST API booking-service. */
public final class Views {
    private Views() {}

    public record Cell(int row, int seat, String state, int price, String by) {}

    public record SeatMapView(SessionInfo session, List<Cell> seats, int sold, int held, int total, int percent,
                              int priceMin, int priceMax, List<String> factors,
                              Long myHoldExpiresAt, boolean full) {}

    public record HoldRequest(Long userId, Long sessionId, List<Dtos.Seat> seats) {}
    public record HoldResult(List<Dtos.Seat> seats, long expiresAt) {}

    public record CheckoutRequest(Long userId, Long sessionId, List<Dtos.Seat> seats, String promoCode,
                                  int usePoints, List<Dtos.BarLine> barItems, String groupId) {}

    public record QuoteView(SessionInfo session, Dtos.PriceQuote tickets, Dtos.BarQuote bar, boolean barAvailable,
                            int pointsAvailable, int maxPoints, int pointsToUse, String level,
                            int total, int earnPoints, Long holdExpiresAt, List<String> messages) {}

    public record TicketView(String id, Long orderId, Long sessionId, Long movieId, String movieTitle, String genre,
                             String cinemaName, String cityName, String hallName, int row, int seat, int price,
                             String status, int refundAmount, LocalDateTime startTime, LocalDateTime usedAt,
                             String groupId, String buyerName, int refundPercent, int refundEstimate) {}

    public record CheckoutResult(Long orderId, int total, int paidAmount, int discount, int pointsUsed,
                                 int barAmount, List<TicketView> tickets, String barItems, List<String> messages) {}

    public record ScanResult(String result, String message, TicketView ticket) {}

    public record Stats(Long sessionId, int sold, int held, int total, int percent) {}
}
