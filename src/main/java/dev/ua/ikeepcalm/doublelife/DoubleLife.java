package dev.ua.ikeepcalm.doublelife;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.doublelife.command.DoubleLifeCommand;
import dev.ua.ikeepcalm.doublelife.config.PluginConfig;
import dev.ua.ikeepcalm.doublelife.domain.service.SessionManager;
import dev.ua.ikeepcalm.doublelife.listener.ActivityListener;
import dev.ua.ikeepcalm.doublelife.listener.CommandInterceptor;
import dev.ua.ikeepcalm.doublelife.listener.PlayerJoinListener;
import dev.ua.ikeepcalm.doublelife.config.LangConfig;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.SerializablePlayerState;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import dev.ua.ikeepcalm.doublelife.util.WebhookUtil;
import lombok.Getter;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public class DoubleLife extends JavaPlugin {

    @Getter
    private static DoubleLife instance;
    private PluginConfig pluginConfig;
    private LangConfig langConfig;
    private SessionManager sessionManager;
    private LuckPerms luckPerms;
    private WebhookUtil webhookUtil;
    private LiteCommands<org.bukkit.command.CommandSender> liteCommands;

    @Override
    public void onEnable() {
        instance = this;
        
        // Register serializable classes for YAML
        ConfigurationSerialization.registerClass(SessionData.class);
        ConfigurationSerialization.registerClass(SerializablePlayerState.class);

        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        this.langConfig = new LangConfig(this);

        if (!setupLuckPerms()) {
            getLogger().severe(langConfig.getMessage("status.luckperms-not-found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.sessionManager = new SessionManager(this);
        this.webhookUtil = new WebhookUtil(this);

        registerCommands();
        registerListeners();

        getLogger().info(langConfig.getMessage("console.plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            try {
                sessionManager.saveSessionsOnShutdown();
                sessionManager.endAllSessions();
            } catch (Exception e) {
                getLogger().severe("Error during session cleanup: " + e.getMessage());
            }
        }

        if (liteCommands != null) {
            try {
                liteCommands.unregister();
            } catch (Exception e) {
                getLogger().severe("Error unregistering commands: " + e.getMessage());
            }
        }

        if (langConfig != null) {
            getLogger().info(langConfig.getMessage("console.plugin-disabled"));
        } else {
            getLogger().info("DoubleLife plugin disabled.");
        }
    }

    private boolean setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
            return true;
        }
        return false;
    }

    private void registerCommands() {
        this.liteCommands = LiteBukkitFactory.builder()
                .commands(new DoubleLifeCommand(this))
                .build();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new ActivityListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandInterceptor(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
    }

    public void reload() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.langConfig.reloadLanguages();
        getLogger().info(langConfig.getMessage("messages.reload-success"));
    }

}