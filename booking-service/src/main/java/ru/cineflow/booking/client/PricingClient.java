package ru.cineflow.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "pricing-service")
public interface PricingClient {
    @PostMapping("/api/pricing/quote")
    Dtos.PriceQuote quote(@RequestBody Dtos.PriceQuoteRequest req);

    @PostMapping("/api/pricing/seat-map")
    Dtos.SeatMapPrices seatMap(@RequestBody Dtos.SessionPricing s);

    @PostMapping("/api/promos/{code}/redeem")
    void redeem(@PathVariable("code") String code);
}
