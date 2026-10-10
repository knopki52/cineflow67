package ru.cineflow.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loyalty")
public class LoyaltyProperties {
    private int basePercent = 5;
    private int silverPercent = 7;
    private int goldPercent = 10;
    private int silverThreshold = 3000;
    private int goldThreshold = 8000;
    private String botUsername = "cineflow_demo_bot";

    public int getBasePercent() { return basePercent; }
    public void setBasePercent(int v) { basePercent = v; }
    public int getSilverPercent() { return silverPercent; }
    public void setSilverPercent(int v) { silverPercent = v; }
    public int getGoldPercent() { return goldPercent; }
    public void setGoldPercent(int v) { goldPercent = v; }
    public int getSilverThreshold() { return silverThreshold; }
    public void setSilverThreshold(int v) { silverThreshold = v; }
    public int getGoldThreshold() { return goldThreshold; }
    public void setGoldThreshold(int v) { goldThreshold = v; }
    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String v) { botUsername = v; }
}
