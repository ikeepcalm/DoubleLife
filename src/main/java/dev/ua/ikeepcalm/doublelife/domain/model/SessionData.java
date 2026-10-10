package dev.ua.ikeepcalm.doublelife.domain.model;

import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import lombok.Getter;
import org.bukkit.configuration.serialization.ConfigurationSerializable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a DoubleLife admin session with state management and persistence support.
 * This class handles both runtime session logic and serialization for server restarts.
 */
@Getter
public class SessionData implements ConfigurationSerializable {

    /** Stable id for this session; correlates every audit row and survives restarts. */
    private final UUID sessionId;
    private final UUID playerId;
    private final PlayerState savedState;
    private Instant startTime;
    private Instant endTime;
    private final List<ActivityLog> activities;
    private final DoubleLifeMode mode;
    private long extensionMinutes = 0;

    public SessionData(UUID playerId, PlayerState savedState, DoubleLifeMode mode) {
        this.sessionId = UUID.randomUUID();
        this.playerId = playerId;
        this.savedState = savedState;
        this.mode = mode;
        this.startTime = Instant.now();
        this.activities = new ArrayList<>();
    }

    public SessionData(UUID playerId, PlayerState savedState, DoubleLifeMode mode, LocalDateTime startTime) {
        this.sessionId = UUID.randomUUID();
        this.playerId = playerId;
        this.savedState = savedState;
        this.mode = mode;
        this.startTime = startTime.atZone(ZoneId.systemDefault()).toInstant();
        this.activities = new ArrayList<>();
    }

    public SessionData(UUID playerId, PlayerState savedState, DoubleLifeMode mode, LocalDateTime startTime, long extensionMinutes) {
        this(UUID.randomUUID(), playerId, savedState, mode, startTime, extensionMinutes);
    }

    public SessionData(UUID sessionId, UUID playerId, PlayerState savedState, DoubleLifeMode mode,
                       LocalDateTime startTime, long extensionMinutes) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.savedState = savedState;
        this.mode = mode;
        this.startTime = startTime.atZone(ZoneId.systemDefault()).toInstant();
        this.activities = new ArrayList<>();
        this.extensionMinutes = extensionMinutes;
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
        // Simply add to the extension counter - don't manipulate startTime
        this.extensionMinutes += extensionMillis / (60 * 1000);
    }

    public long getTotalAllowedMinutes(long baseDurationMinutes) {
        return baseDurationMinutes + extensionMinutes;
    }

    // ConfigurationSerializable implementation

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();

        map.put("sessionId", sessionId.toString());
        map.put("playerId", playerId.toString());
        map.put("startTime", getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (endTime != null) {
            map.put("endTime", LocalDateTime.ofInstant(endTime, ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        map.put("mode", mode.name());
        map.put("extensionMinutes", extensionMinutes);

        if (savedState != null) {
            map.put("savedState", savedState);
        }

        map.put("activities", serializeActivities());

        return map;
    }

    public static SessionData deserialize(Map<String, Object> map) {
        try {
            UUID playerId = UUID.fromString((String) map.get("playerId"));
            DoubleLifeMode mode = DoubleLifeMode.valueOf((String) map.get("mode"));

            String startTimeStr = (String) map.get("startTime");
            LocalDateTime startTime = LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            long extensionMinutes = 0;
            Object extensionObj = map.get("extensionMinutes");
            if (extensionObj != null) {
                extensionMinutes = ((Number) extensionObj).longValue();
            }

            PlayerState savedState = null;
            Object stateObj = map.get("savedState");
            if (stateObj != null) {
                if (stateObj instanceof PlayerState) {
                    savedState = (PlayerState) stateObj;
                } else if (stateObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stateMap = (Map<String, Object>) stateObj;
                    savedState = PlayerState.deserialize(stateMap);
                }
            }

            Object sessionIdObj = map.get("sessionId");
            UUID sessionId = sessionIdObj instanceof String text ? UUID.fromString(text) : UUID.randomUUID();

            SessionData session = new SessionData(sessionId, playerId, savedState, mode, startTime, extensionMinutes);
            session.activities.addAll(deserializeActivities(map.get("activities")));

            // Restore endTime if present (though typically only active sessions are saved)
            String endTimeStr = (String) map.get("endTime");
            if (endTimeStr != null) {
                LocalDateTime endTime = LocalDateTime.parse(endTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                session.endTime = endTime.atZone(ZoneId.systemDefault()).toInstant();
            }

            return session;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize DoubleLifeSession", e);
        }
    }

    private List<Map<String, Object>> serializeActivities() {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ActivityLog activity : activities) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("timestamp", activity.getTimestamp().toString());
            entry.put("type", activity.getType().name());
            entry.put("details", activity.getDetails());
            entry.put("location", activity.getLocation());
            serialized.add(entry);
        }
        return serialized;
    }

    private static List<ActivityLog> deserializeActivities(Object raw) {
        List<ActivityLog> restored = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return restored;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values)) continue;
            try {
                restored.add(new ActivityLog(
                        Instant.parse(String.valueOf(values.get("timestamp"))),
                        ActivityType.valueOf(String.valueOf(values.get("type"))),
                        values.get("details") == null ? "" : String.valueOf(values.get("details")),
                        values.get("location") == null ? "Unknown" : String.valueOf(values.get("location"))));
            } catch (RuntimeException malformed) {
                // Skip an entry that cannot be read so the session still loads
            }
        }
        return restored;
    }
}
