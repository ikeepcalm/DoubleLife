package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RiskAnalyzer {

    private final DoubleLife plugin;

    public RiskAnalyzer(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public RiskAssessment analyze(SessionData session) {
        List<ActivityLog> activities = session.getActivities();
        List<String> flags = new ArrayList<>();
        int score = 0;

        int weightCreativeContainer = plugin.getPluginConfig().getRiskWeightCreativeContainer();
        int weightTeleportDrop = plugin.getPluginConfig().getRiskWeightTeleportDrop();
        int weightItemDropVolume = plugin.getPluginConfig().getRiskWeightItemDropVolume();
        int weightContainerAccess = plugin.getPluginConfig().getRiskWeightContainerAccess();
        int weightBlockBreakVelocity = plugin.getPluginConfig().getRiskWeightBlockBreakVelocity();
        int weightSensitiveCommand = plugin.getPluginConfig().getRiskWeightSensitiveCommand();

        long blockWindowSeconds = plugin.getPluginConfig().getRiskBlockWindowSeconds();
        int blockVelocityThreshold = plugin.getPluginConfig().getRiskBlockVelocityCount();
        long teleportDropWindowSeconds = plugin.getPluginConfig().getRiskTeleportDropWindowSeconds();
        int dropVolumeThreshold = plugin.getPluginConfig().getRiskDropVolumeThreshold();
        int containerAccessThreshold = plugin.getPluginConfig().getRiskContainerAccessThreshold();

        // Track creative state: TURBO always starts in creative; DEFAULT tracks GAMEMODE_CHANGE events
        boolean inCreative = session.getMode() == DoubleLifeMode.TURBO;

        int totalDrops = 0;
        int totalContainerAccesses = 0;
        int totalContainerTransfersInCreative = 0;
        int totalBlockBreaksInCreative = 0;

        for (int i = 0; i < activities.size(); i++) {
            ActivityLog activity = activities.get(i);

            // Track creative transitions
            if (activity.getType() == ActivityType.GAMEMODE_CHANGE) {
                String details = activity.getDetails().toLowerCase();
                if (details.contains("creative")) {
                    inCreative = true;
                } else if (details.contains("survival") || details.contains("adventure") || details.contains("spectator")) {
                    inCreative = false;
                }
            }

            // Creative + container siphoning
            if (inCreative) {
                if (activity.getType() == ActivityType.CONTAINER_TRANSFER) {
                    totalContainerTransfersInCreative++;
                }
                if (activity.getType() == ActivityType.BLOCK_BREAK) {
                    totalBlockBreaksInCreative++;
                }
            }

            // Item drop volume
            if (activity.getType() == ActivityType.ITEM_DROP) {
                totalDrops++;
            }

            // Container access count
            if (activity.getType() == ActivityType.CONTAINER_ACCESS) {
                totalContainerAccesses++;
            }

            // TP-then-drop: teleport (player-caused) followed by item drop within window
            if (activity.getType() == ActivityType.TELEPORT) {
                String details = activity.getDetails().toLowerCase();
                boolean playerTriggered = details.contains("player") || details.contains("tp") || details.contains("teleport");
                if (playerTriggered) {
                    Instant teleportTime = activity.getTimestamp();
                    for (int j = i + 1; j < activities.size(); j++) {
                        ActivityLog next = activities.get(j);
                        long secondsAfter = next.getTimestamp().getEpochSecond() - teleportTime.getEpochSecond();
                        if (secondsAfter > teleportDropWindowSeconds) break;
                        if (next.getType() == ActivityType.ITEM_DROP) {
                            score += weightTeleportDrop;
                            flags.add("Teleported then dropped items within " + teleportDropWindowSeconds + "s (possible item handoff)");
                            break;
                        }
                    }
                }
            }

            // Block-break velocity: count breaks in a rolling window
            if (activity.getType() == ActivityType.BLOCK_BREAK) {
                Instant windowStart = activity.getTimestamp().minusSeconds(blockWindowSeconds);
                int breaksInWindow = 0;
                for (ActivityLog other : activities) {
                    if (other.getType() == ActivityType.BLOCK_BREAK
                            && !other.getTimestamp().isBefore(windowStart)
                            && !other.getTimestamp().isAfter(activity.getTimestamp())) {
                        breaksInWindow++;
                    }
                }
                if (breaksInWindow >= blockVelocityThreshold) {
                    score += weightBlockBreakVelocity;
                    flags.add("High block-break velocity: " + breaksInWindow + " breaks in " + blockWindowSeconds + "s");
                    // Only flag this once by breaking out of the velocity check for this window
                    break;
                }
            }

            // Sensitive command usage — matched against all configured patterns
            if (activity.getType() == ActivityType.COMMAND) {
                String cmd = activity.getDetails();
                for (Pattern pattern : plugin.getPluginConfig().getSensitiveCommandPatterns()) {
                    if (pattern.matcher(cmd).find()) {
                        score += weightSensitiveCommand;
                        flags.add("Sensitive command matched [" + pattern.pattern() + "]: " + cmd);
                        break; // count each command once even if multiple patterns match
                    }
                }
            }
        }

        // Evaluate accumulated totals
        if (totalContainerTransfersInCreative > 0) {
            score += weightCreativeContainer * totalContainerTransfersInCreative;
            flags.add("Accessed " + totalContainerTransfersInCreative + " container(s) while in Creative mode (possible item siphoning)");
        }
        if (totalBlockBreaksInCreative > 0 && totalContainerTransfersInCreative > 0) {
            // Already flagged transfers; add a combined note
            score += weightCreativeContainer;
            flags.add("Broke blocks and transferred items while in Creative — possible stash concealment");
        }
        if (totalDrops >= dropVolumeThreshold) {
            score += weightItemDropVolume * totalDrops;
            flags.add("Dropped items " + totalDrops + " time(s) during session (threshold: " + dropVolumeThreshold + ")");
        }
        if (totalContainerAccesses >= containerAccessThreshold) {
            score += weightContainerAccess * totalContainerAccesses;
            flags.add("Accessed " + totalContainerAccesses + " container(s) (threshold: " + containerAccessThreshold + ")");
        }

        RiskLevel level = scoreToLevel(score);
        return new RiskAssessment(score, level, flags);
    }

    private RiskLevel scoreToLevel(int score) {
        if (score >= 15) return RiskLevel.HIGH;
        if (score >= 6) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
