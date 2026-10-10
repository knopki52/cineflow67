package ru.cineflow.user;

/** То, что видят сайт и бот: профиль + состояние бонусной программы. */
public record UserView(Long id, String name, String email, String role,
                       int points, int totalSpent, String level, int percent,
                       String nextLevel, int toNextLevel, double progress,
                       boolean telegramLinked, String telegramUsername, Long telegramChatId) {}
