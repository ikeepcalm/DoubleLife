package dev.ua.ikeepcalm.doublelife.domain.model.source;

import lombok.Getter;

@Getter
public enum RiskLevel {

    LOW("Low", "🟢", 0x45B7D1),
    MEDIUM("Medium", "🟡", 0xFFA726),
    HIGH("High", "🔴", 0xEF5350);

    private final String displayName;
    private final String emoji;
    private final int discordColor;

    RiskLevel(String displayName, String emoji, int discordColor) {
        this.displayName = displayName;
        this.emoji = emoji;
        this.discordColor = discordColor;
    }

    public boolean isAtLeast(RiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }

    public static RiskLevel fromString(String value) {
        for (RiskLevel level : values()) {
            if (level.name().equalsIgnoreCase(value)) {
                return level;
            }
        }
        return MEDIUM;
    }
}
