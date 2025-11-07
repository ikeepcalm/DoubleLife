package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class LogWriter {

    private final DoubleLife plugin;
    private final SessionData session;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LogWriter(DoubleLife plugin, SessionData session) {
        this.plugin = plugin;
        this.session = session;
    }

    public void writeLog() {
        Player player = Bukkit.getPlayer(session.getPlayerId());
        String playerName = player != null ? player.getName() : session.getPlayerId().toString();

        String filename = playerName + "-" + dateFormat.format(new Date()) + ".log";
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        File logFile = new File(logDir, filename);
        String logContent = formatLog();

        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write(logContent);
            plugin.getLogger().info("Log written: " + filename);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write log: " + e.getMessage());
        }

        plugin.getWebhookUtil().sendLog(playerName, logContent, session);
    }

    private String formatLog() {
        StringBuilder log = new StringBuilder();

        log.append("# DOUBLE LIFE SESSION LOG (").append(session.getMode().getDisplayName().toUpperCase()).append(")\n```");
        log.append("Player: ").append(getPlayerName()).append("\n");
        log.append("UUID: ").append(session.getPlayerId()).append("\n");
        log.append("Mode: ").append(session.getMode().getDisplayName()).append("\n");
        log.append("Start Time: ").append(session.getStartTime()).append("\n");
        log.append("End Time: ").append(session.getEndTime()).append("\n");
        log.append("Duration: ").append(formatDuration()).append("\n");
        log.append("Total Activities: ").append(session.getActivities().size()).append("\n");
        log.append("```\n");

        log.append("# SAVED STATE \n```");
        log.append("Location: ").append(formatLocation(session.getSavedState().getLocation())).append("\n");
        log.append("GameMode: ").append(session.getSavedState().getGameMode()).append("\n");
        log.append("Level: ").append(session.getSavedState().getLevel()).append("\n");
        log.append("Health: ").append(session.getSavedState().getHealth()).append("\n");
        log.append("Food Level: ").append(session.getSavedState().getFoodLevel()).append("\n");
        log.append("```\n");

        log.append("# ACTIVITY LOG \n```");
        for (ActivityLog activity : session.getActivities()) {
            log.append("[").append(activity.getTimestamp().atZone(ZoneId.systemDefault()).format(timeFormat)).append("] ");
            log.append(activity.getType().getDisplayName()).append(": ");
            log.append(activity.getDetails());
            if (activity.getLocation() != null && !activity.getLocation().isEmpty()) {
                log.append(" @ ").append(activity.getLocation());
            }
            log.append("\n");
        }

        log.append("```\n=== END OF LOG ===");

        return log.toString();
    }

    private String getPlayerName() {
        Player player = Bukkit.getPlayer(session.getPlayerId());
        return player != null ? player.getName() : "Unknown";
    }

    private String formatDuration() {
        long minutes = session.getDuration().toMinutes();
        long seconds = session.getDuration().getSeconds() % 60;
        return String.format("%d minutes, %d seconds", minutes, seconds);
    }

    private String formatLocation(org.bukkit.Location loc) {
        if (loc == null) return "Unknown";
        return String.format("%s [%d, %d, %d]",
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
    }

    public String generateActivitySummary() {
        Map<ActivityType, Integer> activityCounts = new HashMap<>();
        
        for (ActivityLog activity : session.getActivities()) {
            activityCounts.merge(activity.getType(), 1, Integer::sum);
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("**Activity Summary:**\n");
        
        for (Map.Entry<ActivityType, Integer> entry : activityCounts.entrySet()) {
            summary.append("• ").append(entry.getValue()).append(" ");
            String activityName = entry.getKey().getDisplayName().toLowerCase();
            
            if (entry.getValue() == 1) {
                summary.append(activityName);
            } else {
                // Make plural
                switch (entry.getKey()) {
                    case COMMAND:
                        summary.append("commands executed");
                        break;
                    case BLOCK_PLACE:
                        summary.append("blocks placed");
                        break;
                    case BLOCK_BREAK:
                        summary.append("blocks broken");
                        break;
                    case ITEM_DROP:
                        summary.append("items dropped");
                        break;
                    case ITEM_PICKUP:
                        summary.append("items picked up");
                        break;
                    case ITEM_GIVE:
                        summary.append("items given");
                        break;
                    case CONTAINER_ACCESS:
                        summary.append("container accesses");
                        break;
                    case CONTAINER_TRANSFER:
                        summary.append("container transfers");
                        break;
                    case TELEPORT:
                        summary.append("teleports");
                        break;
                    case GAMEMODE_CHANGE:
                        summary.append("gamemode changes");
                        break;
                    default:
                        summary.append(activityName).append("s");
                }
            }
            summary.append("\n");
        }
        
        return summary.toString();
    }

    public String formatLogForDiscord(int maxLength) {
        StringBuilder log = new StringBuilder();
        
        // Header with mode emphasis
        String modeEmoji = session.getMode() == DoubleLifeMode.TURBO ? "🚀" : "⚡";
        log.append("# ").append(modeEmoji).append(" DOUBLE LIFE SESSION (").append(session.getMode().getDisplayName().toUpperCase()).append(")\n");
        
        // Basic info
        log.append("**Player:** ").append(getPlayerName()).append("\n");
        log.append("**Duration:** ").append(formatDuration()).append("\n");
        log.append("**Total Activities:** ").append(session.getActivities().size()).append("\n\n");
        
        // Activity summary
        log.append(generateActivitySummary()).append("\n");
        
        // Check if we have room for detailed logs
        int currentLength = log.length();
        int remainingLength = maxLength - currentLength - 100; // Leave room for closing
        
        if (session.getActivities().size() > 0 && remainingLength > 200) {
            log.append("**Recent Activities:**\n```\n");
            
            StringBuilder activityLog = new StringBuilder();
            int activitiesShown = 0;
            int maxActivities = Math.min(10, session.getActivities().size());
            
            // Show last N activities
            for (int i = session.getActivities().size() - 1; i >= 0 && activitiesShown < maxActivities; i--) {
                ActivityLog activity = session.getActivities().get(i);
                String activityLine = "[" + activity.getTimestamp().atZone(ZoneId.systemDefault()).format(timeFormat) + "] " +
                                    activity.getType().getDisplayName() + ": " + activity.getDetails();
                if (activity.getLocation() != null && !activity.getLocation().isEmpty()) {
                    activityLine += " @ " + activity.getLocation();
                }
                activityLine += "\n";
                
                if (activityLog.length() + activityLine.length() + 10 < remainingLength) {
                    activityLog.insert(0, activityLine);
                    activitiesShown++;
                } else {
                    break;
                }
            }
            
            log.append(activityLog);
            
            if (activitiesShown < session.getActivities().size()) {
                log.append("... and ").append(session.getActivities().size() - activitiesShown).append(" more activities\n");
            }
            
            log.append("```");
        }
        
        return log.toString();
    }
}