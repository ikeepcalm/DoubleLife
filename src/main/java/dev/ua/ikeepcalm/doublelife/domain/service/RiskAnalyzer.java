package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.config.PluginConfig;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every flag counts once per session: repeating a legit action (restoring a chest = dozens of transfers)
 * must not stack into HIGH. Self-targeted sensitive commands are the strongest abuse signal.
 */
public class RiskAnalyzer {

    private final DoubleLife plugin;

    public RiskAnalyzer(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public RiskAssessment analyze(SessionData session, String playerName) {
        PluginConfig config = plugin.getPluginConfig();
        List<ActivityLog> activities = session.getActivities();
        List<String> flags = new ArrayList<>();
        int score = 0;

        boolean inCreative = session.getMode() == DoubleLifeMode.TURBO;
        int drops = 0;
        int containerAccesses = 0;
        int creativeTransfers = 0;
        int creativeBreaks = 0;
        int teleportDrops = 0;
        int peakBreaks = 0;
        Set<String> sensitivePatterns = new LinkedHashSet<>();
        List<String> selfTargeted = new ArrayList<>();

        for (int i = 0; i < activities.size(); i++) {
            ActivityLog activity = activities.get(i);
            switch (activity.getType()) {
                case GAMEMODE_CHANGE -> {
                    String details = activity.getDetails().toLowerCase();
                    if (details.endsWith("creative")) inCreative = true;
                    else if (details.endsWith("survival") || details.endsWith("adventure") || details.endsWith("spectator")) inCreative = false;
                }
                case CONTAINER_TRANSFER -> { if (inCreative) creativeTransfers++; }
                case CONTAINER_ACCESS -> containerAccesses++;
                case ITEM_DROP -> drops++;
                case BLOCK_BREAK -> {
                    if (inCreative) creativeBreaks++;
                    Instant windowStart = activity.getTimestamp().minusSeconds(config.getRiskBlockWindowSeconds());
                    int inWindow = 0;
                    for (int j = i; j >= 0 && !activities.get(j).getTimestamp().isBefore(windowStart); j--) {
                        if (activities.get(j).getType() == ActivityType.BLOCK_BREAK) inWindow++;
                    }
                    peakBreaks = Math.max(peakBreaks, inWindow);
                }
                case TELEPORT -> {
                    Instant teleportTime = activity.getTimestamp();
                    for (int j = i + 1; j < activities.size(); j++) {
                        ActivityLog next = activities.get(j);
                        if (next.getTimestamp().getEpochSecond() - teleportTime.getEpochSecond() > config.getRiskTeleportDropWindowSeconds()) break;
                        if (next.getType() == ActivityType.ITEM_DROP) {
                            teleportDrops++;
                            break;
                        }
                    }
                }
                case COMMAND -> {
                    String cmd = activity.getDetails();
                    for (Pattern pattern : config.getSensitiveCommandPatterns()) {
                        if (pattern.matcher(cmd).find()) {
                            sensitivePatterns.add(pattern.pattern());
                            if (isSelfTargeted(cmd, playerName)) selfTargeted.add(cmd);
                            break;
                        }
                    }
                }
                default -> {
                }
            }
        }

        if (!selfTargeted.isEmpty()) {
            score += config.getRiskWeightSelfTargeted();
            flags.add("Sensitive command(s) targeting themselves: " + String.join(", ", selfTargeted.subList(0, Math.min(5, selfTargeted.size()))));
        }
        if (!sensitivePatterns.isEmpty()) {
            score += config.getRiskWeightSensitiveCommand() * sensitivePatterns.size();
            flags.add("Used sensitive command type(s): " + String.join(", ", sensitivePatterns));
        }
        if (teleportDrops > 0) {
            score += config.getRiskWeightTeleportDrop();
            flags.add("Teleported then dropped items within " + config.getRiskTeleportDropWindowSeconds() + "s, " + teleportDrops + " time(s) (possible item handoff)");
        }
        if (creativeTransfers > 0) {
            score += config.getRiskWeightCreativeContainer();
            flags.add("Moved items in containers " + creativeTransfers + " time(s) while in Creative");
            if (creativeBreaks > 0) {
                score += config.getRiskWeightCreativeContainer();
                flags.add("Broke blocks and moved items while in Creative (possible stash concealment)");
            }
        }
        if (peakBreaks >= config.getRiskBlockVelocityCount()) {
            score += config.getRiskWeightBlockBreakVelocity();
            flags.add("High block-break velocity: " + peakBreaks + " breaks in " + config.getRiskBlockWindowSeconds() + "s");
        }
        if (drops >= config.getRiskDropVolumeThreshold()) {
            score += config.getRiskWeightItemDropVolume();
            flags.add("Dropped items " + drops + " time(s)");
        }
        if (containerAccesses >= config.getRiskContainerAccessThreshold()) {
            score += config.getRiskWeightContainerAccess();
            flags.add("Opened " + containerAccesses + " container(s)");
        }

        RiskLevel level = score >= config.getRiskHighScore() ? RiskLevel.HIGH
                : score >= config.getRiskMediumScore() ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskAssessment(score, level, flags);
    }

    static boolean isSelfTargeted(String command, String playerName) {
        String[] tokens = command.toLowerCase().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            // @a and @e include the sender too
            if (token.startsWith("@s") || token.startsWith("@p") || token.startsWith("@a") || token.startsWith("@e")
                    || token.equals(playerName.toLowerCase())) return true;
        }
        return false;
    }
}
