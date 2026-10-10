package ru.cineflow.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.booking.model.WaitlistEntry;
import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {
    Optional<WaitlistEntry> findFirstBySessionIdAndStatusOrderByCreatedAtAsc(Long sessionId, String status);
    List<WaitlistEntry> findBySessionIdAndStatusOrderByCreatedAtAsc(Long sessionId, String status);
    Optional<WaitlistEntry> findFirstBySessionIdAndUserIdAndStatusIn(Long sessionId, Long userId, List<String> statuses);
    List<WaitlistEntry> findByUserIdAndStatusIn(Long userId, List<String> statuses);
}
