package dev.ua.ikeepcalm.doublelife;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor;
import dev.ua.ikeepcalm.doublelife.audit.AuditEmitter;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor;
import dev.ua.ikeepcalm.doublelife.command.DoubleLifeCommand;
import dev.ua.ikeepcalm.doublelife.config.PluginConfig;
import dev.ua.ikeepcalm.doublelife.domain.service.SessionManager;
import dev.ua.ikeepcalm.doublelife.listener.ActivityListener;
import dev.ua.ikeepcalm.doublelife.listener.AuditListener;
import dev.ua.ikeepcalm.doublelife.listener.InventoryAuditListener;
import dev.ua.ikeepcalm.doublelife.listener.CommandInterceptor;
import dev.ua.ikeepcalm.doublelife.listener.PlayerJoinListener;
import dev.ua.ikeepcalm.doublelife.config.LangConfig;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import dev.ua.ikeepcalm.doublelife.domain.service.OpGuardService;
import dev.ua.ikeepcalm.doublelife.util.GeminiClient;
import dev.ua.ikeepcalm.doublelife.util.SessionReporter;
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
    private GeminiClient geminiClient;
    private SessionReporter sessionReporter;
    private OpGuardService opGuardService;
    private LiteCommands<CommandSender> liteCommands;
    private AuditEmitter auditEmitter;
    private SessionAuditor sessionAuditor;
    private ActivityAuditor activityAuditor;
    private ActivityListener activityListener;

    @Override
    public void onEnable() {
        instance = this;
        
        // Register serializable classes for YAML
        ConfigurationSerialization.registerClass(SessionData.class);
        ConfigurationSerialization.registerClass(PlayerState.class);

        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        this.langConfig = new LangConfig(this);

        this.auditEmitter = new AuditEmitter(this);
        this.sessionAuditor = new SessionAuditor(auditEmitter);
        this.activityAuditor = new ActivityAuditor(auditEmitter, () -> pluginConfig.getSensitiveCommandPatterns());

        if (!setupLuckPerms()) {
            getLogger().severe(langConfig.getMessage("status.luckperms-not-found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.sessionManager = new SessionManager(this);
        this.webhookUtil = new WebhookUtil(this);
        this.geminiClient = new GeminiClient(this);
        this.sessionReporter = new SessionReporter(this);
        this.opGuardService = new OpGuardService(this);

        registerCommands();
        registerListeners();

        // Sweep for unauthorised operators every 20 ticks
        getServer().getScheduler().runTaskTimer(this,
                () -> opGuardService.checkAllOnlinePlayers(), 20L, 20L);
        // Async: the batcher is synchronized and its rows are emitted off the main thread.
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> activityAuditor.flushStale(), 100L, 100L);

        getLogger().info(langConfig.getMessage("console.plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            try {
                // Ends every session synchronously, then saves only sessions that could not be restored.
                sessionManager.shutdown();
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

        if (auditEmitter != null) {
            auditEmitter.close();
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
        getServer().getPluginManager().registerEvents(activityListener = new ActivityListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandInterceptor(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new AuditListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryAuditListener(this), this);
    }

    public void reload() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.langConfig.reloadLanguages();
        getLogger().info(langConfig.getMessage("messages.reload-success"));
    }

}