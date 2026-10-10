package ru.cineflow.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LoyaltyTxRepository extends JpaRepository<LoyaltyTx, Long> {
    List<LoyaltyTx> findByOrderIdAndType(Long orderId, String type);
    List<LoyaltyTx> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
