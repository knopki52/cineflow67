package ru.cineflow.bar;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MenuSeeder implements ApplicationRunner {
    private final MenuRepository menu;

    public MenuSeeder(MenuRepository menu) { this.menu = menu; }

    @Override
    public void run(ApplicationArguments args) {
        if (menu.count() > 0) return;
        menu.save(new MenuItem("Попкорн солёный", "🍿", "POPCORN", 290, "Классика, большое ведро"));
        menu.save(new MenuItem("Попкорн карамельный", "🍯", "POPCORN", 340, "Хрустящая карамель"));
        menu.save(new MenuItem("Попкорн сырный", "🧀", "POPCORN", 340, "С сырной посыпкой"));
        menu.save(new MenuItem("Кола 0,5", "🥤", "DRINK", 190, "Со льдом"));
        menu.save(new MenuItem("Лимонад «Тархун»", "🍋", "DRINK", 210, "Домашний"));
        menu.save(new MenuItem("Вода", "💧", "DRINK", 120, "Без газа"));
        menu.save(new MenuItem("Начос с сырным соусом", "🌮", "SNACK", 320, "Тёплые"));
        menu.save(new MenuItem("Комбо «Двое в зале»", "🎞", "COMBO", 690, "Большой попкорн и две колы"));
    }
}
