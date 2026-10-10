package ru.cineflow.pricing.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "promo_codes")
public class PromoCode {
    @Id
    public String code;
    /** PERCENT или FIXED */
    public String type;
    public int amount;
    public String description;
    public LocalDate validUntil;
    public int maxUses;
    public int uses;
    /** Если задан — работает только в этот день недели (MONDAY..SUNDAY) */
    public String dayOfWeek;
    public boolean morningOnly;
    public boolean active = true;
}
