package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Builder
public class SerializableSessionData {
    
    private String playerId;
    private String mode;
    private String startTime;
    private SerializablePlayerState savedState;
    private long durationSeconds;
    
    public static SerializableSessionData fromSession(DoubleLifeSession session) {
        SerializablePlayerState serializedState = null;
        try {
            serializedState = SerializablePlayerState.fromPlayerState(session.getSavedState());
        } catch (Exception e) {
            // If state serialization fails, we'll create a minimal session without state
            serializedState = SerializablePlayerState.builder()
                .level(0)
                .experience(0f)
                .foodLevel(20)
                .saturation(20f)
                .health(20.0)
                .gameMode("SURVIVAL")
                .build();
        }
        
        return SerializableSessionData.builder()
            .playerId(session.getPlayerId().toString())
            .mode(session.getMode().name())
            .startTime(session.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .savedState(serializedState)
            .durationSeconds(session.getDuration().getSeconds())
            .build();
    }
    
    public LocalDateTime getStartTimeAsDateTime() {
        try {
            return LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}