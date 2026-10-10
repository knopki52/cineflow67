package ru.cineflow.pricing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.cineflow.pricing.dto.PricingDtos.*;
import ru.cineflow.pricing.model.PromoCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PricingService {
    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    private final PricingProperties p;
    private final PromoRepository promos;

    public PricingService(PricingProperties p, PromoRepository promos) {
        this.p = p; this.promos = promos;
    }

    /** Множители, которые зависят от сеанса целиком (время и заполненность). */
    private double sessionMultiplier(SessionPricing s, List<String> factors) {
        double k = 1.0;
        int hour = s.startTime().getHour();
        if (hour < p.getMorningUntilHour()) {
            k *= p.getMorningCoefficient();
            factors.add("Утренний сеанс ×" + fmt(p.getMorningCoefficient()));
        }
        DayOfWeek d = s.startTime().getDayOfWeek();
        if ((d == DayOfWeek.FRIDAY || d == DayOfWeek.SATURDAY) && hour >= p.getEveningFromHour()) {
            k *= p.getWeekendEveningCoefficient();
            factors.add("Вечер выходных ×" + fmt(p.getWeekendEveningCoefficient()));
        }
        if (s.occupancy() >= p.getOccupancyThreshold()) {
            k *= p.getOccupancyCoefficient();
            factors.add("Зал заполнен на " + Math.round(s.occupancy() * 100) + "% ×" + fmt(p.getOccupancyCoefficient()));
        }
        return k;
    }

    private double seatMultiplier(SessionPricing s, int row, int seat, List<String> factors) {
        double k = 1.0;
        if (row <= p.getFrontRows()) {
            k *= p.getFrontRowsCoefficient();
            factors.add("Первые ряды ×" + fmt(p.getFrontRowsCoefficient()));
        } else if (isCenter(s, row, seat)) {
            k *= p.getCenterCoefficient();
            factors.add("Центр зала ×" + fmt(p.getCenterCoefficient()));
        }
        return k;
    }

    private boolean isCenter(SessionPricing s, int row, int seat) {
        boolean rowCenter = row > s.rows() * 0.3 && row <= Math.ceil(s.rows() * 0.75);
        boolean seatCenter = seat > s.seatsPerRow() * 0.25 && seat <= Math.ceil(s.seatsPerRow() * 0.75);
        return rowCenter && seatCenter;
    }

    private int round(double v) {
        int r = Math.max(1, p.getRoundTo());
        return (int) (Math.round(v / r) * r);
    }

    public SeatMapPrices seatMap(SessionPricing s) {
        List<String> sessionFactors = new ArrayList<>();
        double sk = sessionMultiplier(s, sessionFactors);
        int[][] prices = new int[s.rows()][s.seatsPerRow()];
        int min = Integer.MAX_VALUE, max = 0;
        for (int r = 1; r <= s.rows(); r++) {
            for (int c = 1; c <= s.seatsPerRow(); c++) {
                int price = round(s.basePrice() * sk * seatMultiplier(s, r, c, new ArrayList<>()));
                prices[r - 1][c - 1] = price;
                min = Math.min(min, price);
                max = Math.max(max, price);
            }
        }
        return new SeatMapPrices(prices, min, max, sessionFactors);
    }

    public Quote quote(QuoteRequest req) {
        SessionPricing s = req.session();
        List<SeatPrice> items = new ArrayList<>();
        int subtotal = 0;
        for (Seat seat : req.seats()) {
            List<String> factors = new ArrayList<>();
            double k = sessionMultiplier(s, factors) * seatMultiplier(s, seat.row(), seat.seat(), factors);
            int price = round(s.basePrice() * k);
            items.add(new SeatPrice(seat.row(), seat.seat(), price, factors));
            subtotal += price;
        }
        int discount = 0;
        boolean applied = false;
        String message = null;
        String code = req.promoCode() == null ? null : req.promoCode().trim().toUpperCase(Locale.ROOT);
        if (code != null && !code.isEmpty()) {
            PromoCode promo = promos.findById(code).orElse(null);
            String problem = validate(promo, s);
            if (problem == null) {
                discount = "PERCENT".equals(promo.type)
                        ? round(subtotal * promo.amount / 100.0)
                        : Math.min(subtotal, promo.amount);
                applied = true;
                message = promo.description;
            } else {
                message = problem;
            }
        }
        return new Quote(items, subtotal, discount, subtotal - discount, code, applied, message);
    }

    private String validate(PromoCode promo, SessionPricing s) {
        if (promo == null || !promo.active) return "Такого промокода нет";
        if (promo.validUntil != null && promo.validUntil.isBefore(LocalDate.now())) return "Срок действия промокода истёк";
        if (promo.maxUses > 0 && promo.uses >= promo.maxUses) return "Промокод уже использован максимальное число раз";
        if (promo.dayOfWeek != null && !promo.dayOfWeek.isBlank()
                && !s.startTime().getDayOfWeek().name().equalsIgnoreCase(promo.dayOfWeek)) {
            return "Промокод действует только " + dayName(promo.dayOfWeek);
        }
        if (promo.morningOnly && s.startTime().getHour() >= p.getMorningUntilHour()) {
            return "Промокод действует только на утренние сеансы";
        }
        return null;
    }

    @Transactional
    public void redeem(String code) {
        promos.findById(code.toUpperCase(Locale.ROOT)).ifPresent(pc -> {
            pc.uses++;
            log.info("Промокод {} использован ({} из {})", pc.code, pc.uses, pc.maxUses == 0 ? "∞" : pc.maxUses);
        });
    }

    private static String dayName(String d) {
        return switch (d.toUpperCase(Locale.ROOT)) {
            case "MONDAY" -> "по понедельникам";
            case "TUESDAY" -> "по вторникам";
            case "WEDNESDAY" -> "по средам";
            case "THURSDAY" -> "по четвергам";
            case "FRIDAY" -> "по пятницам";
            case "SATURDAY" -> "по субботам";
            default -> "по воскресеньям";
        };
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
