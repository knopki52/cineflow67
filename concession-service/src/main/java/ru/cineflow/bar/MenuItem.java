package ru.cineflow.bar;

import jakarta.persistence.*;

@Entity
@Table(name = "menu_items")
public class MenuItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String emoji;
    /** POPCORN, DRINK, COMBO, SNACK */
    public String category;
    public int price;
    public String description;

    public MenuItem() {}
    public MenuItem(String name, String emoji, String category, int price, String description) {
        this.name = name; this.emoji = emoji; this.category = category; this.price = price; this.description = description;
    }
}
