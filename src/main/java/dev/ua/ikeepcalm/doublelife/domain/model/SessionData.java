package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;
import org.bukkit.configuration.serialization.ConfigurationSerializable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class SessionData implements ConfigurationSerializable {
    
    private String playerName;
    private String playerId;
    private String startTime;
    private String endTime;
    private String sessionType; // "default" or "turbo"
    private SerializablePlayerState savedState;
    private long extensionMinutes;
    
    public static SessionData fromSession(DoubleLifeSession session, String playerName) {
        return SessionData.builder()
            .playerName(playerName)
            .playerId(session.getPlayerId().toString())
            .startTime(session.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .endTime(null) // null for active sessions
            .sessionType(session.getMode().name().toLowerCase())
            .savedState(SerializablePlayerState.fromPlayerState(session.getSavedState()))
            .extensionMinutes(session.getExtensionMinutes())
            .build();
    }
    
    public LocalDateTime getStartTimeAsDateTime() {
        try {
            return LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
    
    public LocalDateTime getEndTimeAsDateTime() {
        if (endTime == null) return null;
        try {
            return LocalDateTime.parse(endTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("playerName", playerName);
        map.put("playerId", playerId);
        map.put("startTime", startTime);
        if (endTime != null) {
            map.put("endTime", endTime);
        }
        map.put("sessionType", sessionType);
        if (savedState != null) {
            map.put("savedState", savedState.serialize());
        }
        map.put("extensionMinutes", extensionMinutes);
        return map;
    }
    
    public static SessionData deserialize(Map<String, Object> map) {
        SessionDataBuilder builder = SessionData.builder();
        
        builder.playerName((String) map.get("playerName"));
        builder.playerId((String) map.get("playerId"));
        builder.startTime((String) map.get("startTime"));
        builder.endTime((String) map.get("endTime"));
        builder.sessionType((String) map.get("sessionType"));

        @SuppressWarnings("unchecked")
        Map<String, Object> stateMap = (Map<String, Object>) map.get("savedState");
        if (stateMap != null) {
            builder.savedState(SerializablePlayerState.deserialize(stateMap));
        }

        Object extensionMinutesObj = map.get("extensionMinutes");
        if (extensionMinutesObj != null) {
            builder.extensionMinutes(((Number) extensionMinutesObj).longValue());
        }
        
        return builder.build();
    }
}