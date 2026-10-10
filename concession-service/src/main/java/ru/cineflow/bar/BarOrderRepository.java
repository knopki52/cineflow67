package ru.cineflow.bar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BarOrderRepository extends JpaRepository<BarOrder, Long> {
    List<BarOrder> findTop100ByOrderByCreatedAtDesc();
    List<BarOrder> findByStatusInOrderByStartTimeAsc(List<String> statuses);
    List<BarOrder> findByBookingOrderId(Long bookingOrderId);
}
