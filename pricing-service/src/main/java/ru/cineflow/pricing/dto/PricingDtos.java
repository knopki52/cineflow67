package ru.cineflow.pricing.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class PricingDtos {
    private PricingDtos() {}

    public record Seat(int row, int seat) {}

    /** Данные о сеансе, достаточные для расчёта цены. */
    public record SessionPricing(LocalDateTime startTime, int basePrice, int rows, int seatsPerRow, double occupancy) {}

    public record QuoteRequest(SessionPricing session, List<Seat> seats, String promoCode) {}

    public record SeatPrice(int row, int seat, int price, List<String> factors) {}

    public record Quote(List<SeatPrice> items, int subtotal, int discount, int total,
                        String promoCode, boolean promoApplied, String promoMessage) {}

    /** Цены для всей схемы зала: prices[row-1][seat-1] */
    public record SeatMapPrices(int[][] prices, int min, int max, List<String> sessionFactors) {}
}
