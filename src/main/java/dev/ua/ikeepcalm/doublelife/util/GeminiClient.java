package dev.ua.ikeepcalm.doublelife.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends session context to the Gemini API and returns a one-sentence verdict.
 * All network calls are blocking — callers must run this off the main thread.
 */
public class GeminiClient {

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String FALLBACK = "AI summary unavailable.";

    private final DoubleLife plugin;

    public GeminiClient(DoubleLife plugin) {
        this.plugin = plugin;
    }

    /**
     * Requests a one-sentence audit verdict from Gemini for the given session context.
     * Blocking — must be called from an async thread.
     *
     * @param playerName    the session player's name
     * @param activityLog   the pre-formatted activity log string
     * @param assessment    the computed risk assessment (flags used to focus the prompt)
     * @return a single-sentence verdict string, or FALLBACK on any error
     */
    public String requestVerdict(String playerName, String activityLog, RiskAssessment assessment) {
        String apiKey = plugin.getPluginConfig().getAiApiKey();
        String model = plugin.getPluginConfig().getAiModel();

        if (apiKey == null || apiKey.isBlank()) {
            plugin.getLogger().warning("GeminiClient: ai.api-key is not set — skipping AI summary.");
            return FALLBACK;
        }

        try {
            String prompt = buildPrompt(playerName, activityLog, assessment);
            String requestBody = buildRequestBody(prompt);

            String endpoint = String.format(API_BASE, model, apiKey);
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                plugin.getLogger().warning("GeminiClient: API returned HTTP " + status);
                return FALLBACK;
            }

            try (InputStream is = conn.getInputStream()) {
                String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return parseVerdict(responseBody);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("GeminiClient: request failed — " + e.getMessage());
            return FALLBACK;
        }
    }

    private String buildPrompt(String playerName, String activityLog, RiskAssessment assessment) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Minecraft server anti-abuse auditor. ");
        sb.append("A staff member just ended an administrative session. ");
        sb.append("Your job: in EXACTLY ONE concise sentence, state whether this session shows ");
        sb.append("evidence of item duplication, stashing, favoritism, or other rule violations — ");
        sb.append("or confirm it looks clean. Do not add any formatting, bullet points, or extra lines.\n\n");

        sb.append("Player: ").append(playerName).append("\n");
        sb.append("Risk score: ").append(assessment.getScore())
          .append(" (").append(assessment.getLevel().getDisplayName()).append(")\n");

        if (assessment.hasFlags()) {
            sb.append("Detected flags:\n").append(assessment.formatFlags()).append("\n\n");
        }

        sb.append("Activity log (chronological):\n");
        sb.append(activityLog);
        return sb.toString();
    }

    private String buildRequestBody(String prompt) {
        // Escape prompt for JSON embedding
        String escaped = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        return "{\"contents\":[{\"parts\":[{\"text\":\"" + escaped + "\"}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":120,\"temperature\":0.2}}";
    }

    private String parseVerdict(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return FALLBACK;

            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            if (content == null) return FALLBACK;

            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) return FALLBACK;

            String text = parts.get(0).getAsJsonObject().get("text").getAsString().trim();
            return text.isEmpty() ? FALLBACK : text;

        } catch (Exception e) {
            plugin.getLogger().warning("GeminiClient: failed to parse response — " + e.getMessage());
            return FALLBACK;
        }
    }
}
