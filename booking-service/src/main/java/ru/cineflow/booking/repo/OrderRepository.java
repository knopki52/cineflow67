package ru.cineflow.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.booking.model.BookingOrder;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<BookingOrder, Long> {
    List<BookingOrder> findByStatusInAndCreatedAtAfter(List<String> statuses, LocalDateTime after);
    List<BookingOrder> findByUserIdAndStatusIn(Long userId, List<String> statuses);
}
