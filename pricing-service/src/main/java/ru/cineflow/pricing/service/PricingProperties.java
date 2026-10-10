package ru.cineflow.pricing.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Правила цен из config-repo/pricing-service.yml.
 * При POST /actuator/refresh Spring Cloud заново привязывает эти свойства — без перезапуска сервиса.
 */
@ConfigurationProperties(prefix = "pricing")
public class PricingProperties {
    private String banner = "";
    private int roundTo = 10;
    private double centerCoefficient = 1.3;
    private int frontRows = 2;
    private double frontRowsCoefficient = 0.8;
    private int morningUntilHour = 12;
    private double morningCoefficient = 0.7;
    private int eveningFromHour = 18;
    private double weekendEveningCoefficient = 1.2;
    private double occupancyThreshold = 0.7;
    private double occupancyCoefficient = 1.2;

    public String getBanner() { return banner; }
    public void setBanner(String banner) { this.banner = banner; }
    public int getRoundTo() { return roundTo; }
    public void setRoundTo(int roundTo) { this.roundTo = roundTo; }
    public double getCenterCoefficient() { return centerCoefficient; }
    public void setCenterCoefficient(double v) { this.centerCoefficient = v; }
    public int getFrontRows() { return frontRows; }
    public void setFrontRows(int frontRows) { this.frontRows = frontRows; }
    public double getFrontRowsCoefficient() { return frontRowsCoefficient; }
    public void setFrontRowsCoefficient(double v) { this.frontRowsCoefficient = v; }
    public int getMorningUntilHour() { return morningUntilHour; }
    public void setMorningUntilHour(int v) { this.morningUntilHour = v; }
    public double getMorningCoefficient() { return morningCoefficient; }
    public void setMorningCoefficient(double v) { this.morningCoefficient = v; }
    public int getEveningFromHour() { return eveningFromHour; }
    public void setEveningFromHour(int v) { this.eveningFromHour = v; }
    public double getWeekendEveningCoefficient() { return weekendEveningCoefficient; }
    public void setWeekendEveningCoefficient(double v) { this.weekendEveningCoefficient = v; }
    public double getOccupancyThreshold() { return occupancyThreshold; }
    public void setOccupancyThreshold(double v) { this.occupancyThreshold = v; }
    public double getOccupancyCoefficient() { return occupancyCoefficient; }
    public void setOccupancyCoefficient(double v) { this.occupancyCoefficient = v; }
}
