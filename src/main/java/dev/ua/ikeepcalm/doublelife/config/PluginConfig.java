package dev.ua.ikeepcalm.doublelife.config;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

@Getter
public class PluginConfig {
    
    private final DoubleLife plugin;
    private final FileConfiguration config;
    
    private final long maxDuration;
    private final long cooldownDuration;
    private final List<String> temporaryPermissions;
    private final List<String> entryCommands;
    
    private final String language;
    
    private final boolean guiEnabled;
    private final String guiTitle;
    private final String guiStartButton;
    private final String guiEndButton;
    
    private final boolean logCommands;
    private final boolean logGamemodeChanges;
    private final boolean logItemGive;
    private final boolean logContainerAccess;
    private final boolean logBlockPlacements;
    private final boolean logItemDrops;
    
    private final boolean discordWebhookEnabled;
    private final String discordWebhookUrl;
    private final String discordWebhookFormat;
    
    private final boolean callbackEnabled;
    private final String callbackUrl;
    private final String callbackMethod;
    
    public PluginConfig(DoubleLife plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        
        this.maxDuration = parseDuration(config.getString("max-duration", "10m"));
        this.cooldownDuration = parseDuration(config.getString("cooldown", "5m"));
        this.temporaryPermissions = config.getStringList("temporary-permissions");
        this.entryCommands = config.getStringList("entry-commands");
        
        this.language = config.getString("language.default", "en");
        
        this.guiEnabled = config.getBoolean("gui.enabled", true);
        this.guiTitle = config.getString("gui.title", "⚡ Double Life Menu");
        this.guiStartButton = config.getString("gui.start-button", "Start Admin Mode");
        this.guiEndButton = config.getString("gui.end-button", "End Admin Mode");
        
        this.logCommands = config.getBoolean("logging.commands", true);
        this.logGamemodeChanges = config.getBoolean("logging.gamemode-changes", true);
        this.logItemGive = config.getBoolean("logging.item-give", true);
        this.logContainerAccess = config.getBoolean("logging.container-access", true);
        this.logBlockPlacements = config.getBoolean("logging.block-placements", true);
        this.logItemDrops = config.getBoolean("logging.item-drops", true);
        
        this.discordWebhookEnabled = config.getBoolean("webhook.discord.enabled", false);
        this.discordWebhookUrl = config.getString("webhook.discord.url", "");
        this.discordWebhookFormat = config.getString("webhook.discord.format", "markdown");
        
        this.callbackEnabled = config.getBoolean("webhook.callback.enabled", false);
        this.callbackUrl = config.getString("webhook.callback.url", "");
        this.callbackMethod = config.getString("webhook.callback.method", "POST");
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
}