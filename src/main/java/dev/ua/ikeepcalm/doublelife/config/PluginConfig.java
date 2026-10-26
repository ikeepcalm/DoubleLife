package dev.ua.ikeepcalm.doublelife.config;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.configuration.ConfigurationSection;

@Getter
public class PluginConfig {
    
    private final DoubleLife plugin;
    private final FileConfiguration config;
    
    private final long maxDuration;
    private final long cooldownDuration;
    private final List<String> temporaryPermissions;
    private final long maxExtension;
    private final boolean requireReason;
    private final List<String> defaultPermissions;
    private final List<String> entryCommands;
    private final List<String> exitCommands;
    private final Map<String, List<String>> groupCommands;
    private final List<String> alwaysAllowedCommands;
    
    private final String language;
    
    private final boolean guiEnabled;
    
    private final boolean logCommands;
    private final boolean logGamemodeChanges;
    private final boolean logItemGive;
    private final boolean logContainerAccess;
    private final boolean logBlockPlacements;
    private final boolean logItemDrops;
    
    private final boolean discordWebhookEnabled;
    private final String discordWebhookUrl;
    private final String discordWebhookFormat;
    private final boolean discordTurboMention;
    
    private final boolean callbackEnabled;
    private final String callbackUrl;
    private final String callbackMethod;

    // AI
    private final boolean aiEnabled;
    private final String aiApiKey;
    private final String aiModel;

    // Risk scoring
    private final RiskLevel riskThreshold;
    private final int riskWeightCreativeContainer;
    private final int riskWeightTeleportDrop;
    private final int riskWeightItemDropVolume;
    private final int riskWeightContainerAccess;
    private final int riskWeightBlockBreakVelocity;
    private final int riskWeightSensitiveCommand;
    private final int riskWeightSelfTargeted;
    private final int riskMediumScore;
    private final int riskHighScore;
    private final RiskLevel riskPingThreshold;
    private final int historyRetentionDays;
    private final long pingCooldownMinutes;
    private final long riskBlockWindowSeconds;
    private final int riskBlockVelocityCount;
    private final long riskTeleportDropWindowSeconds;
    private final int riskDropVolumeThreshold;
    private final int riskContainerAccessThreshold;

    // Risk sensitive-command patterns (compiled from config strings)
    private final List<Pattern> sensitiveCommandPatterns;

    // Notifications
    private final boolean cleanSummaryEnabled;

    // Op whitelist
    private final boolean opWhitelistEnabled;
    private final Set<String> allowedOps;

    public PluginConfig(DoubleLife plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();

        this.maxDuration = parseDuration(config.getString("max-duration", "10m"));
        this.cooldownDuration = parseDuration(config.getString("cooldown", "5m"));
        this.temporaryPermissions = config.getStringList("temporary-permissions");
        this.maxExtension = parseDuration(config.getString("max-extension", "10m"));
        this.requireReason = config.getBoolean("require-reason", true);
        this.defaultPermissions = config.getStringList("default-permissions");
        this.entryCommands = config.getStringList("entry-commands");
        this.exitCommands = config.getStringList("exit-commands");
        this.groupCommands = loadGroupCommands();
        this.alwaysAllowedCommands = config.getStringList("always-allowed-commands").stream().map(String::toLowerCase).toList();

        this.language = config.getString("language.default", "en");

        this.guiEnabled = config.getBoolean("gui.enabled", true);

        this.logCommands = config.getBoolean("logging.commands", true);
        this.logGamemodeChanges = config.getBoolean("logging.gamemode-changes", true);
        this.logItemGive = config.getBoolean("logging.item-give", true);
        this.logContainerAccess = config.getBoolean("logging.container-access", true);
        this.logBlockPlacements = config.getBoolean("logging.block-placements", true);
        this.logItemDrops = config.getBoolean("logging.item-drops", true);

        this.discordWebhookEnabled = config.getBoolean("webhook.discord.enabled", false);
        this.discordWebhookUrl = config.getString("webhook.discord.url", "");
        this.discordWebhookFormat = config.getString("webhook.discord.format", "markdown");
        this.discordTurboMention = config.getBoolean("webhook.discord.turbo-mention", true);

        this.callbackEnabled = config.getBoolean("webhook.callback.enabled", false);
        this.callbackUrl = config.getString("webhook.callback.url", "");
        this.callbackMethod = config.getString("webhook.callback.method", "POST");

        // AI
        this.aiEnabled = config.getBoolean("ai.enabled", false);
        this.aiApiKey = config.getString("ai.api-key", "");
        this.aiModel = config.getString("ai.model", "gemini-2.0-flash");

        // Risk weights
        this.riskThreshold = RiskLevel.fromString(config.getString("risk.threshold", "MEDIUM"));
        this.riskWeightCreativeContainer = config.getInt("risk.weights.creative-container", 5);
        this.riskWeightTeleportDrop = config.getInt("risk.weights.teleport-then-drop", 8);
        this.riskWeightItemDropVolume = config.getInt("risk.weights.item-drop-volume", 3);
        this.riskWeightContainerAccess = config.getInt("risk.weights.container-access", 2);
        this.riskWeightBlockBreakVelocity = config.getInt("risk.weights.block-break-velocity", 3);
        this.riskWeightSensitiveCommand = config.getInt("risk.weights.sensitive-command", 2);
        this.riskWeightSelfTargeted = config.getInt("risk.weights.self-targeted", 8);
        this.riskMediumScore = config.getInt("risk.levels.medium", 6);
        this.riskHighScore = config.getInt("risk.levels.high", 15);
        this.riskPingThreshold = RiskLevel.fromString(config.getString("risk.ping-threshold", "HIGH"));
        this.historyRetentionDays = config.getInt("history.retention-days", 90);
        this.pingCooldownMinutes = parseDuration(config.getString("notifications.ping-cooldown", "30m"));
        this.riskBlockWindowSeconds = config.getLong("risk.velocity.block-window-seconds", 5L);
        this.riskBlockVelocityCount = config.getInt("risk.velocity.block-count", 30);
        this.riskTeleportDropWindowSeconds = config.getLong("risk.teleport-drop-window-seconds", 15L);
        this.riskDropVolumeThreshold = config.getInt("risk.thresholds.drop-volume", 5);
        this.riskContainerAccessThreshold = config.getInt("risk.thresholds.container-access", 5);

        // Sensitive command patterns
        this.sensitiveCommandPatterns = compileSensitiveCommandPatterns(config.getStringList("risk.sensitive-commands"), plugin);

        // Notifications
        this.cleanSummaryEnabled = config.getBoolean("notifications.clean-summary", true);

        // Op whitelist
        this.opWhitelistEnabled = config.getBoolean("op-whitelist.enabled", false);
        List<String> rawOps = config.getStringList("op-whitelist.allowed-ops");
        Set<String> ops = new HashSet<>();
        for (String name : rawOps) {
            ops.add(name.toLowerCase());
        }
        this.allowedOps = ops;
    }
    
    private static List<Pattern> compileSensitiveCommandPatterns(List<String> raw, DoubleLife plugin) {
        List<Pattern> compiled = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            try {
                compiled.add(Pattern.compile(entry, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid regex in risk.sensitive-commands: \"" + entry + "\" — " + e.getDescription());
            }
        }
        return compiled;
    }

    private long parseDuration(String duration) {
        if (duration == null) return 10;
        
        duration = duration.trim().toLowerCase();
        if (duration.endsWith("m")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1));
        } else if (duration.endsWith("h")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1)) * 60;
        }
        
        return Long.parseLong(duration);
    }
    
    private Map<String, List<String>> loadGroupCommands() {
        Map<String, List<String>> result = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("group-commands");
        
        if (section == null) {
            return result;
        }
        
        for (String groupName : section.getKeys(false)) {
            result.put(groupName, section.getStringList(groupName).stream().map(String::toLowerCase).toList());
        }
        
        return result;
    }
}