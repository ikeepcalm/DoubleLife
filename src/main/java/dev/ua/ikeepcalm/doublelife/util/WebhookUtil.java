package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
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

    public void sendLog(String playerName, String logContent, SessionData session) {
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

    private void sendDiscordWebhook(String playerName, String logContent, SessionData session) {
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

    private String formatForDiscord(String playerName, String logContent, String format, SessionData session) {
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

    private String formatSessionDuration(SessionData session) {
        long minutes = session.getDuration().toMinutes();
        long seconds = session.getDuration().getSeconds() % 60;
        return String.format("%dm %ds", minutes, seconds);
    }

    /**
     * Posts a high-priority flagged-session alert.  Called from an already-async task.
     */
    public void sendFlaggedAlert(String playerName, SessionData session, RiskAssessment assessment, String aiVerdict) {
        if (!plugin.getPluginConfig().isDiscordWebhookEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
                if (webhookUrl.isEmpty()) return;

                LogWriter logWriter = new LogWriter(plugin, session);
                String detailBlock = escapeJson(logWriter.formatLogForDiscord(1400));

                String mention = plugin.getPluginConfig().isDiscordTurboMention() ? "@here " : "";
                String levelEmoji = assessment.getLevel().getEmoji();
                int color = assessment.getLevel().getDiscordColor();

                String flagsSection = escapeJson(assessment.formatFlags());
                String verdictLine = escapeJson(aiVerdict);

                String description = "**AI Verdict:** " + verdictLine + "\\n\\n"
                        + "**Risk Score:** " + assessment.getScore()
                        + " (" + assessment.getLevel().getDisplayName() + ")\\n\\n"
                        + "**Flags:**\\n" + flagsSection + "\\n\\n"
                        + detailBlock;

                String payload = "{"
                        + "\"content\": \"" + mention + levelEmoji + " **Suspicious session detected**\","
                        + "\"embeds\": [{"
                        + "\"title\": \"" + levelEmoji + " " + escapeJson(session.getMode().getDisplayName())
                        + " Session — " + escapeJson(playerName) + "\","
                        + "\"description\": \"" + description + "\","
                        + "\"color\": " + color + ","
                        + "\"timestamp\": \"" + session.getEndTime().toString() + "\","
                        + "\"footer\": {\"text\": \"Activities: " + session.getActivities().size()
                        + " | Duration: " + formatSessionDuration(session) + "\"}"
                        + "}]"
                        + "}";

                postWebhook(webhookUrl, payload, "flagged alert");
                sendCallbackIfEnabled(playerName, logWriter.formatLog(session));
            } catch (Exception e) {
                plugin.getLogger().severe("Error sending flagged alert: " + e.getMessage());
            }
        });
    }

    /**
     * Posts a quiet one-line summary for a clean (below-threshold) session.
     * Called from an already-async task.  No mention, green colour.
     */
    public void sendCleanSummary(String playerName, SessionData session, RiskAssessment assessment) {
        if (!plugin.getPluginConfig().isDiscordWebhookEnabled()) return;
        if (!plugin.getPluginConfig().isCleanSummaryEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
                if (webhookUrl.isEmpty()) return;

                String modeEmoji = session.getMode() == DoubleLifeMode.TURBO ? "🚀" : "⚡";
                String description = "Session ended with no suspicious activity detected. "
                        + "Score: " + assessment.getScore()
                        + " | Activities: " + session.getActivities().size()
                        + " | Duration: " + formatSessionDuration(session);

                String payload = "{"
                        + "\"embeds\": [{"
                        + "\"title\": \"" + modeEmoji + " " + escapeJson(session.getMode().getDisplayName())
                        + " Session — " + escapeJson(playerName) + "\","
                        + "\"description\": \"" + escapeJson(description) + "\","
                        + "\"color\": 4568217,"  // muted green #45B7D9
                        + "\"timestamp\": \"" + session.getEndTime().toString() + "\","
                        + "\"footer\": {\"text\": \"Risk: LOW\"}"
                        + "}]"
                        + "}";

                postWebhook(webhookUrl, payload, "clean summary");

                LogWriter logWriter = new LogWriter(plugin, session);
                sendCallbackIfEnabled(playerName, logWriter.formatLog(session));
            } catch (Exception e) {
                plugin.getLogger().severe("Error sending clean summary: " + e.getMessage());
            }
        });
    }

    private void postWebhook(String webhookUrl, String payload, String label) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == 204) {
                plugin.getLogger().info("Discord webhook (" + label + ") sent successfully");
            } else {
                plugin.getLogger().warning("Discord webhook (" + label + ") failed: " + code);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error posting webhook (" + label + "): " + e.getMessage());
        }
    }

    private void sendCallbackIfEnabled(String playerName, String logContent) {
        if (plugin.getPluginConfig().isCallbackEnabled()) {
            sendHttpCallback(playerName, logContent);
        }
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