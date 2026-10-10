package ru.cineflow.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "concession-service")
public interface ConcessionClient {
    @PostMapping("/api/bar/quote")
    Dtos.BarQuote quote(@RequestBody Dtos.BarQuoteRequest req);

    @PostMapping("/api/bar/orders")
    Dtos.BarOrderView create(@RequestBody Dtos.BarCreate req);

    @PostMapping("/api/bar/orders/by-booking/{orderId}/cancel")
    void cancelByBooking(@PathVariable("orderId") Long orderId);
}
