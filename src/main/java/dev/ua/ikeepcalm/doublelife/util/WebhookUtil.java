package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeSession;
import dev.ua.ikeepcalm.doublelife.util.LogWriter;
import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebhookUtil {

    private final DoubleLife plugin;

    public WebhookUtil(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public void sendLog(String playerName, String logContent, DoubleLifeSession session) {
        if (plugin.getPluginConfig().isDiscordWebhookEnabled()) {
            sendDiscordWebhook(playerName, logContent, session);
        }

        if (plugin.getPluginConfig().isCallbackEnabled()) {
            sendHttpCallback(playerName, logContent);
        }
    }

    public void sendTurboModeActivation(String playerName) {
        if (plugin.getPluginConfig().isDiscordWebhookEnabled()) {
            sendTurboActivationWebhook(playerName);
        }
    }

    private void sendDiscordWebhook(String playerName, String logContent, DoubleLifeSession session) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
                if (webhookUrl.isEmpty()) return;

                String format = plugin.getPluginConfig().getDiscordWebhookFormat();
                String content = formatForDiscord(playerName, logContent, format, session);

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = content.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 204) {
                    plugin.getLogger().info("Discord webhook sent successfully");
                } else {
                    plugin.getLogger().warning("Discord webhook failed: " + responseCode);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error sending Discord webhook: " + e.getMessage());
            }
        });
    }

    private void sendHttpCallback(String playerName, String logContent) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String callbackUrl = plugin.getPluginConfig().getCallbackUrl();
                if (callbackUrl.isEmpty()) return;

                URL url = new URL(callbackUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(plugin.getPluginConfig().getCallbackMethod());
                conn.setRequestProperty("Content-Type", "application/json");

                String authHeader = plugin.getConfig().getString("webhook.callback.headers.Authorization", "");
                if (!authHeader.isEmpty()) {
                    conn.setRequestProperty("Authorization", authHeader);
                }

                conn.setDoOutput(true);

                String jsonPayload = createJsonPayload(playerName, logContent);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                plugin.getLogger().info("HTTP callback sent: " + responseCode);

            } catch (Exception e) {
                plugin.getLogger().severe("Error sending HTTP callback: " + e.getMessage());
            }
        });
    }

    private String formatForDiscord(String playerName, String logContent, String format, DoubleLifeSession session) {
        LogWriter logWriter = new LogWriter(plugin, session);
        
        if (format.equalsIgnoreCase("markdown")) {
            // Use smart Discord formatting with activity summary
            String discordContent = logWriter.formatLogForDiscord(1800); // Leave room for JSON structure
            
            // Ensure proper code block closure
            if (discordContent.contains("```") && !discordContent.trim().endsWith("```")) {
                discordContent = ensureCodeBlockClosure(discordContent);
            }
            
            // Choose color and content based on mode
            int embedColor = session.getMode() == DoubleLifeMode.TURBO ? 16711680 : 255; // Red for Turbo, Blue for Default
            String modeEmoji = session.getMode() == DoubleLifeMode.TURBO ? "🚀" : "⚡";
            String attentionLevel = (session.getMode() == DoubleLifeMode.TURBO && plugin.getPluginConfig().isDiscordTurboMention()) ? "@here " : "";
            
            return "{"
                   + "\"content\": \"" + attentionLevel + modeEmoji + " **Double Life Session Ended**\","
                   + "\"embeds\": [{"
                   + "\"title\": \"" + session.getMode().getDisplayName() + " Session - " + escapeJson(playerName) + "\","
                   + "\"description\": \"" + escapeJson(discordContent) + "\","
                   + "\"color\": " + embedColor + ","
                   + "\"timestamp\": \"" + session.getEndTime().toString() + "\","
                   + "\"footer\": {"
                   + "\"text\": \"Activities: " + session.getActivities().size() + " | Duration: " + formatSessionDuration(session) + "\""
                   + "}"
                   + "}]"
                   + "}";
        } else {
            // File attachment format
            String attentionLevel = (session.getMode() == DoubleLifeMode.TURBO && plugin.getPluginConfig().isDiscordTurboMention()) ? "@here " : "";
            String modeEmoji = session.getMode() == DoubleLifeMode.TURBO ? "🚀" : "⚡";
            
            return "{"
                   + "\"content\": \"" + attentionLevel + modeEmoji + " Double Life " + session.getMode().getDisplayName() + " session log for " + escapeJson(playerName) + ":\","
                   + "\"files\": [{"
                   + "\"name\": \"" + escapeJson(playerName) + "-" + session.getMode().name().toLowerCase() + "-session.log\","
                   + "\"content\": \"" + escapeJson(logContent) + "\""
                   + "}]"
                   + "}";
        }
    }

    private String createJsonPayload(String playerName, String logContent) {
        return "{"
               + "\"player\": \"" + escapeJson(playerName) + "\","
               + "\"timestamp\": " + System.currentTimeMillis() + ","
               + "\"log\": \"" + escapeJson(logContent) + "\""
               + "}";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeDiscordMarkdown(String text) {
        return text.replace("*", "\\*")
                .replace("_", "\\_");
    }

    private String ensureCodeBlockClosure(String content) {
        // Count unclosed code blocks
        String[] parts = content.split("```");
        
        // If odd number of parts, we have an unclosed code block
        if (parts.length % 2 == 0) {
            content += "\n```";
        }
        
        return content;
    }

    private String formatSessionDuration(DoubleLifeSession session) {
        long minutes = session.getDuration().toMinutes();
        long seconds = session.getDuration().getSeconds() % 60;
        return String.format("%dm %ds", minutes, seconds);
    }

    private void sendTurboActivationWebhook(String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
                if (webhookUrl.isEmpty()) return;

                // Create immediate high-priority notification for turbo mode activation
                String attentionLevel = plugin.getPluginConfig().isDiscordTurboMention() ? "@here " : "";
                String content = "{"
                        + "\"content\": \"" + attentionLevel + "🚀 **TURBO MODE ACTIVATED** 🚀\","
                        + "\"embeds\": [{"
                        + "\"title\": \"⚠️ High Priority Alert\","
                        + "\"description\": \"**" + escapeJson(playerName) + "** has activated Turbo Double Life mode with full administrative permissions.\\n\\n🔒 **Enhanced monitoring is now active**\","
                        + "\"color\": 16711680," // Red color for high priority
                        + "\"timestamp\": \"" + java.time.Instant.now().toString() + "\","
                        + "\"footer\": {"
                        + "\"text\": \"Immediate alert - Session log will follow when ended\""
                        + "}"
                        + "}]"
                        + "}";

                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = content.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 204) {
                    plugin.getLogger().info("Turbo mode activation webhook sent successfully");
                } else {
                    plugin.getLogger().warning("Turbo mode activation webhook failed: " + responseCode);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error sending turbo activation webhook: " + e.getMessage());
            }
        });
    }
}