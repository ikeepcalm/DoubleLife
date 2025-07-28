package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
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

    public void sendLog(String playerName, String logContent) {
        if (plugin.getPluginConfig().isDiscordWebhookEnabled()) {
            sendDiscordWebhook(playerName, logContent);
        }

        if (plugin.getPluginConfig().isCallbackEnabled()) {
            sendHttpCallback(playerName, logContent);
        }
    }

    private void sendDiscordWebhook(String playerName, String logContent) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
                if (webhookUrl.isEmpty()) return;

                String format = plugin.getPluginConfig().getDiscordWebhookFormat();
                String content = formatForDiscord(playerName, logContent, format);

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

    private String formatForDiscord(String playerName, String logContent, String format) {
        if (format.equalsIgnoreCase("markdown")) {
            String escaped = escapeDiscordMarkdown(logContent);
            if (escaped.length() > 1900) {
                escaped = escaped.substring(0, 1900) + "...\n[Log truncated]";
            }

            return "{"
                   + "\"content\": \"**Double Life Session Ended**\","
                   + "\"embeds\": [{"
                   + "\"title\": \"Session Log - " + escapeJson(playerName) + "\","
                   + "\"description\": \"\\n" + escapeJson(escaped) + "\\n\","
                   + "\"color\": 16744448"
                   + "}]"
                   + "}";
        } else {
            return "{"
                   + "\"content\": \"Double Life session log for " + escapeJson(playerName) + ":\","
                   + "\"files\": [{"
                   + "\"name\": \"" + escapeJson(playerName) + "-session.log\","
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
}