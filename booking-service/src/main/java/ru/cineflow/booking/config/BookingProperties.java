package ru.cineflow.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "booking")
public class BookingProperties {
    private int holdMinutes = 5;
    private int groupHoldMinutes = 10;
    private int waitlistHoldMinutes = 10;
    private int maxSeatsPerUser = 8;

    public int getHoldMinutes() { return holdMinutes; }
    public void setHoldMinutes(int v) { holdMinutes = v; }
    public int getGroupHoldMinutes() { return groupHoldMinutes; }
    public void setGroupHoldMinutes(int v) { groupHoldMinutes = v; }
    public int getWaitlistHoldMinutes() { return waitlistHoldMinutes; }
    public void setWaitlistHoldMinutes(int v) { waitlistHoldMinutes = v; }
    public int getMaxSeatsPerUser() { return maxSeatsPerUser; }
    public void setMaxSeatsPerUser(int v) { maxSeatsPerUser = v; }
}
