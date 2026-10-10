package ru.cineflow.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    Optional<AppUser> findByTelegramChatId(Long chatId);
    Optional<AppUser> findByLinkCode(String code);
}
