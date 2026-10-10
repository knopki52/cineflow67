package ru.cineflow.pricing.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.cineflow.pricing.model.PromoCode;

import java.time.LocalDate;

@Component
public class PromoSeeder implements ApplicationRunner {
    private final PromoRepository promos;

    public PromoSeeder(PromoRepository promos) { this.promos = promos; }

    @Override
    public void run(ApplicationArguments args) {
        if (promos.count() > 0) return;
        save("AUTUMN", "PERCENT", 20, "Осенний марафон −20%", LocalDate.now().plusMonths(2), 0, null, false);
        save("STUDENT", "PERCENT", 15, "Студенческая скидка −15%", null, 0, null, false);
        save("TUESDAY30", "PERCENT", 30, "Кино-вторник −30%", null, 0, "TUESDAY", false);
        save("MORNING", "PERCENT", 25, "Утренний сеанс −25%", null, 0, null, true);
        save("FRIEND", "FIXED", 150, "Приведи друга: −150 ₽", null, 100, null, false);
    }

    private void save(String code, String type, int amount, String description, LocalDate until,
                      int maxUses, String day, boolean morning) {
        PromoCode p = new PromoCode();
        p.code = code; p.type = type; p.amount = amount; p.description = description;
        p.validUntil = until; p.maxUses = maxUses; p.dayOfWeek = day; p.morningOnly = morning;
        promos.save(p);
    }
}
