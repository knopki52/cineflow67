package ru.cineflow.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service")
public interface PaymentClient {
    @PostMapping("/api/payments")
    Dtos.PaymentResult pay(@RequestBody Dtos.PaymentRequest req);

    @PostMapping("/api/payments/refund")
    Dtos.PaymentResult refund(@RequestBody Dtos.RefundRequest req);
}
