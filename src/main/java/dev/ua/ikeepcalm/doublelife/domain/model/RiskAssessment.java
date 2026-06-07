package dev.ua.ikeepcalm.doublelife.domain.model;

import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import lombok.Getter;

import java.util.List;

@Getter
public class RiskAssessment {

    private final int score;
    private final RiskLevel level;
    private final List<String> flags;

    public RiskAssessment(int score, RiskLevel level, List<String> flags) {
        this.score = score;
        this.level = level;
        this.flags = flags;
    }

    public boolean hasFlags() {
        return !flags.isEmpty();
    }

    public String formatFlags() {
        if (flags.isEmpty()) {
            return "No suspicious activity detected.";
        }
        StringBuilder sb = new StringBuilder();
        for (String flag : flags) {
            sb.append("• ").append(flag).append("\n");
        }
        return sb.toString().trim();
    }
}
