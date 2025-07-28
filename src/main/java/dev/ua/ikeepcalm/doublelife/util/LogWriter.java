package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class LogWriter {

    private final DoubleLife plugin;
    private final DoubleLifeSession session;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LogWriter(DoubleLife plugin, DoubleLifeSession session) {
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

        plugin.getWebhookUtil().sendLog(playerName, logContent);
    }

    private String formatLog() {
        StringBuilder log = new StringBuilder();

        log.append("# DOUBLE LIFE SESSION LOG \n```");
        log.append("Player: ").append(getPlayerName()).append("\n");
        log.append("UUID: ").append(session.getPlayerId()).append("\n");
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
}