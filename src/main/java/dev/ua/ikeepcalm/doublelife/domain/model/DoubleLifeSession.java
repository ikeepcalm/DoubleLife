package dev.ua.ikeepcalm.doublelife.domain.model;

import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class DoubleLifeSession {
    
    private final UUID playerId;
    private final PlayerState savedState;
    private Instant startTime;
    private Instant endTime;
    private final List<ActivityLog> activities;
    private final DoubleLifeMode mode;
    
    public DoubleLifeSession(UUID playerId, PlayerState savedState, DoubleLifeMode mode) {
        this.playerId = playerId;
        this.savedState = savedState;
        this.mode = mode;
        this.startTime = Instant.now();
        this.activities = new ArrayList<>();
    }

    public DoubleLifeSession(UUID playerId, PlayerState savedState, DoubleLifeMode mode, LocalDateTime startTime) {
        this.playerId = playerId;
        this.savedState = savedState;
        this.mode = mode;
        this.startTime = startTime.atZone(ZoneId.systemDefault()).toInstant();
        this.activities = new ArrayList<>();
    }

    public void logActivity(ActivityLog activity) {
        activities.add(activity);
    }
    
    public void logActivity(ActivityType type, String details, String location) {
        activities.add(new ActivityLog(type, details, location));
    }
    
    public Duration getDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }
    
    public void end() {
        this.endTime = Instant.now();
    }
    
    public boolean isActive() {
        return endTime == null;
    }

    public LocalDateTime getStartTime() {
        return LocalDateTime.ofInstant(startTime, ZoneId.systemDefault());
    }

    public void extendSession(long extensionMillis) {
        this.startTime = this.startTime.minusMillis(extensionMillis);
    }
}