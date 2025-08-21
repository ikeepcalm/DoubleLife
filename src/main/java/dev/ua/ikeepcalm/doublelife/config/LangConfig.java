package dev.ua.ikeepcalm.doublelife.config;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public class LangConfig {
    
    private final DoubleLife plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private String defaultLanguage = "en";
    
    public LangConfig(DoubleLife plugin) {
        this.plugin = plugin;
        loadLanguages();
        this.defaultLanguage = plugin.getPluginConfig().getLanguage();
    }
    
    private void loadLanguages() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        
        saveDefaultLanguageFile("en.yml");
        saveDefaultLanguageFile("uk.yml");

        File[] langFiles = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (langFiles != null) {
            for (File langFile : langFiles) {
                String langCode = langFile.getName().replace(".yml", "");
                try {
                    FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);
                    
                    InputStream defConfigStream = plugin.getResource("lang/" + langFile.getName());
                    if (defConfigStream != null) {
                        YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream));
                        config.setDefaults(defConfig);
                    }
                    
                    languages.put(langCode, config);
                    plugin.getLogger().info("Loaded language: " + langCode);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load language file: " + langFile.getName(), e);
                }
            }
        }
        
        if (!languages.containsKey(defaultLanguage)) {
            plugin.getLogger().warning("Default language '" + defaultLanguage + "' not found! Plugin messages may not work correctly.");
        }
    }
    
    private void saveDefaultLanguageFile(String filename) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + filename);
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + filename, false);
        }
    }
    
    public String getMessage(String key, String language) {
        FileConfiguration config = languages.get(language);
        if (config == null) {
            config = languages.get(defaultLanguage);
        }
        
        if (config == null) {
            return "Missing language config for key: " + key;
        }
        
        return config.getString(key, "Missing translation: " + key);
    }
    
    public String getMessage(String key) {
        return getMessage(key, defaultLanguage);
    }
    
    public String getMessage(String key, String language, Object... placeholders) {
        String message = getMessage(key, language);
        
        for (int i = 0; i < placeholders.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(placeholders[i]));
        }
        
        return message;
    }
    
    public String getMessage(String key, Object... placeholders) {
        return getMessage(key, defaultLanguage, placeholders);
    }
    
    public String getMessage(String key, Player player) {
        return getMessage(key, getPlayerLanguage(player));
    }
    
    public String getMessage(String key, Player player, Object... placeholders) {
        return getMessage(key, getPlayerLanguage(player), placeholders);
    }
    
    public List<String> getMessageList(String key) {
        return getMessageList(key, defaultLanguage);
    }
    
    public List<String> getMessageList(String key, String language) {
        FileConfiguration config = languages.get(language);
        if (config == null) {
            config = languages.get(defaultLanguage);
        }
        
        if (config == null) {
            return List.of("Missing language config for key: " + key);
        }
        
        return config.getStringList(key);
    }
    
    public List<String> getMessageList(String key, Object... placeholders) {
        return getMessageList(key, defaultLanguage, placeholders);
    }
    
    public List<String> getMessageList(String key, Player player) {
        return getMessageList(key, getPlayerLanguage(player));
    }
    
    public List<String> getMessageList(String key, Player player, Object... placeholders) {
        return getMessageList(key, getPlayerLanguage(player), placeholders);
    }
    
    public List<String> getMessageList(String key, String language, Object... placeholders) {
        List<String> messages = getMessageList(key, language);
        List<String> result = new ArrayList<>();
        
        for (String message : messages) {
            for (int i = 0; i < placeholders.length; i++) {
                message = message.replace("{" + i + "}", String.valueOf(placeholders[i]));
            }
            result.add(message);
        }
        
        return result;
    }
    
    public void setDefaultLanguage(String language) {
        if (languages.containsKey(language)) {
            this.defaultLanguage = language;
        } else {
            plugin.getLogger().warning("Attempted to set unknown language as default: " + language);
        }
    }
    
    private String getPlayerLanguage(Player player) {
        if (player == null) {
            return defaultLanguage;
        }
        
        Locale playerLocale = player.locale();
        String playerLanguage = playerLocale.getLanguage();
        
        // Try exact language match first (e.g., "en", "uk")
        if (languages.containsKey(playerLanguage)) {
            return playerLanguage;
        }
        
        // Try full locale string as fallback (e.g., "en_US", "uk_UA")
        String fullLocale = playerLocale.toString().toLowerCase();
        if (languages.containsKey(fullLocale)) {
            return fullLocale;
        }
        
        // Fallback to default language
        return defaultLanguage;
    }
    
    public void reloadLanguages() {
        languages.clear();
        loadLanguages();
        this.defaultLanguage = plugin.getPluginConfig().getLanguage();
    }
}