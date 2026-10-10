package ru.cineflow.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.cineflow.booking.model.Ticket;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findBySessionIdAndStatusIn(Long sessionId, Collection<String> statuses);
    List<Ticket> findByUserIdOrderByStartTimeDesc(Long userId);
    List<Ticket> findByOrderId(Long orderId);
    List<Ticket> findByGroupId(String groupId);
    List<Ticket> findByStatusInAndCreatedAtAfter(Collection<String> statuses, LocalDateTime after);
    List<Ticket> findByStatusAndRemindedFalseAndStartTimeBetween(String status, LocalDateTime from, LocalDateTime to);
    boolean existsByUserIdAndMovieIdAndStatus(Long userId, Long movieId, String status);

    /** Атомарная отметка: если запущено два экземпляра сервиса, напоминание уйдёт только один раз. */
    @Modifying
    @Transactional
    @Query("update Ticket t set t.reminded = true where t.id = :id and t.reminded = false")
    int markReminded(@Param("id") String id);
}
