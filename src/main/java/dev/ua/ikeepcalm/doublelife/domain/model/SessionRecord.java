package dev.ua.ikeepcalm.doublelife.domain.model;

import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import lombok.Data;

import java.util.List;

@Data
public class SessionRecord {

    private String playerId;
    private String playerName;
    private DoubleLifeMode mode;
    private long startMillis;
    private long endMillis;
    private String reason;
    private int score;
    private RiskLevel riskLevel;
    private boolean flagged;
    private String aiVerdict;
    private List<String> flags;
    private int activityCount;
    private List<Entry> activities;

    @Data
    public static class Entry {
        private long millis;
        private ActivityType type;
        private String details;
        private String location;
    }
}
