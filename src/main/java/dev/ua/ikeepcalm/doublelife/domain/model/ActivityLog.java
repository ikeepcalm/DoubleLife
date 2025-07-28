package dev.ua.ikeepcalm.doublelife.domain.model;

import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class ActivityLog {
    private final Instant timestamp;
    private final ActivityType type;
    private final String details;
    private final String location;
    
    public ActivityLog(ActivityType type, String details, String location) {
        this.timestamp = Instant.now();
        this.type = type;
        this.details = details;
        this.location = location;
    }
}