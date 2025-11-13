package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import dev.ua.ikeepcalm.doublelife.util.LogWriter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, SessionData> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private List<SessionData> pendingSessions = new ArrayList<>();

    private static final String SESSIONS_FOLDER = "sessions";

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
        loadPendingSessionsFromFile();
    }

    public boolean canStartSession(Player player) {
        return canStartSession(player, DoubleLifeMode.DEFAULT);
    }

    public boolean canStartSession(Player player, DoubleLifeMode mode) {
        if (hasActiveSession(player)) {
            return false;
        }

        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return false;
        }

        // Check basic permission first
        if (!player.hasPermission("doublelife.use")) {
            return false;
        }

        // Check turbo-specific permission for turbo mode
        if (mode == DoubleLifeMode.TURBO && !player.hasPermission("doublelife.turbo")) {
            return false;
        }

        return true;
    }

    public boolean hasTurboPermission(Player player) {
        return player.hasPermission("doublelife.turbo");
    }

    public void startSession(Player player, DoubleLifeMode mode) {
        if (!canStartSession(player, mode)) {
            return;
        }

        PlayerState savedState = PlayerState.capture(player);
        SessionData session = new SessionData(player.getUniqueId(), savedState, mode);
        activeSessions.put(player.getUniqueId(), session);

        // Clear inventory and execute entry commands for both modes
        player.getInventory().clear();
        executeEntryCommands(player);

        if (mode == DoubleLifeMode.TURBO) {
            applyAdminMode(player);
            // Send immediate Discord notification for turbo mode activation
            plugin.getWebhookUtil().sendTurboModeActivation(player.getName());
        }

        startTimer(player, session);
        createBossBar(player, mode);

        String modeMessage = mode == DoubleLifeMode.TURBO ? "session.turbo-start-success" : "session.default-start-success";
        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage(modeMessage, player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-started", player.getName(), mode.getDisplayName()));
    }

    public void startSession(Player player) {
        startSession(player, DoubleLifeMode.DEFAULT);
    }

    public void endSession(Player player) {
        SessionData session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        session.end();
        restorePlayerState(player, session);
        
        if (session.getMode() == DoubleLifeMode.TURBO) {
            removeAdminMode(player);
        }

        BukkitTask timer = sessionTimers.remove(player.getUniqueId());
        if (timer != null) {
            timer.cancel();
        }

        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }

        LogWriter logWriter = new LogWriter(plugin, session);
        logWriter.writeLog();

        long cooldownDuration = plugin.getPluginConfig().getCooldownDuration() * 1000L;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownDuration);

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success", player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-ended", player.getName()));
    }

    public void endAllSessions() {
        Set<UUID> sessionIds = new HashSet<>(activeSessions.keySet());
        for (UUID playerId : sessionIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                endSession(player);
            }
        }
    }

    public void saveSessionsOnShutdown() {
        if (activeSessions.isEmpty()) {
            return;
        }

        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            sessionsFolder.mkdirs();
        }

        int savedCount = 0;
        for (Map.Entry<UUID, SessionData> entry : activeSessions.entrySet()) {
            try {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) continue;

                SessionData session = entry.getValue();
                saveSessionToYaml(session, player.getName());
                savedCount++;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save session for player " + entry.getKey() + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Saved " + savedCount + " active sessions to individual YAML files");
    }

    private void saveSessionToYaml(SessionData session, String playerName) {
        try {
            File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
            File sessionFile = new File(sessionsFolder, playerName + ".yml");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("session", session);
            yaml.save(sessionFile);

            plugin.getLogger().info("Saved session for " + playerName +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save session YAML for " + playerName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadPendingSessionsFromFile() {
        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            return;
        }

        File[] sessionFiles = sessionsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (sessionFiles == null || sessionFiles.length == 0) {
            return;
        }

        int loadedCount = 0;
        for (File sessionFile : sessionFiles) {
            try {
                SessionData session = loadSessionFromYaml(sessionFile);
                if (session != null) {
                    pendingSessions.add(session);
                    loadedCount++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load session file " + sessionFile.getName() + ": " + e.getMessage());
                // Delete corrupted file
                sessionFile.delete();
            }
        }

        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending sessions from YAML files");
        }
    }

    private SessionData loadSessionFromYaml(File sessionFile) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);

            Object sessionObj = yaml.get("session");
            SessionData session = null;

            if (sessionObj instanceof SessionData) {
                session = (SessionData) sessionObj;
            } else if (sessionObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sessionMap = (Map<String, Object>) sessionObj;
                session = SessionData.deserialize(sessionMap);
            }

            if (session == null) {
                plugin.getLogger().warning("Failed to deserialize session from " + sessionFile.getName());
                sessionFile.delete();
                return null;
            }

            plugin.getLogger().info("Successfully loaded session from " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

            sessionFile.delete();

            return session;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load session YAML from " + sessionFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    public void restoreSessionForPlayer(Player player) {
        if (pendingSessions.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        SessionData sessionToRestore = null;

        for (SessionData session : pendingSessions) {
            if (session.getPlayerId().equals(playerId)) {
                sessionToRestore = session;
                break;
            }
        }

        if (sessionToRestore == null) {
            return;
        }

        try {
            if (hasActiveSession(player)) {
                plugin.getLogger().warning("Player " + player.getName() + " already has an active session, skipping restoration");
                pendingSessions.remove(sessionToRestore);
                return;
            }

            long baseDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = sessionToRestore.getTotalAllowedMinutes(baseDuration);
            if (sessionToRestore.getDuration().toMinutes() >= totalAllowedMinutes) {
                plugin.getLogger().info("Session for " + player.getName() + " has expired, not restoring");
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.expired-during-restart", player)));
                pendingSessions.remove(sessionToRestore);
                return;
            }

            activeSessions.put(playerId, sessionToRestore);

            if (sessionToRestore.getMode() == DoubleLifeMode.TURBO) {
                applyAdminMode(player);
            }

            startTimer(player, sessionToRestore);
            createBossBar(player, sessionToRestore.getMode());

            player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.restored-after-restart", player)));

            plugin.getLogger().info("Restored " + sessionToRestore.getMode().getDisplayName() + " session for " + player.getName());

            pendingSessions.remove(sessionToRestore);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore session for player " + player.getName() + ": " + e.getMessage());
            pendingSessions.remove(sessionToRestore);
        }
    }

    private void applyAdminMode(Player player) {
        plugin.getLogger().info("Applying admin mode for " + player.getName());
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            plugin.getLogger().info("User was not found in LuckPerms, withdrawing...");
            return;
        }

        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();

        for (String permission : permissions) {
            Node node = PermissionNode.builder(permission)
                    .expiry(Duration.ofMinutes(plugin.getPluginConfig().getMaxDuration()))
                    .build();
            user.data().add(node);
            plugin.getLogger().info("Adding permission " + permission + " to " + player.getName());
        }

        plugin.getLuckPerms().getUserManager().saveUser(user);
        plugin.getLogger().info("Saved nodes!");
    }

    private void removeAdminMode(Player player) {
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) return;

        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        NodeMap nodeMap = user.getData(DataType.NORMAL);

        for (String permission : permissions) {
            user.data().clear(n -> n.getKey().equals(permission));

            if (nodeMap.remove(Node.builder(permission).build()) == DataMutateResult.SUCCESS) {
                plugin.getLogger().info(plugin.getLangConfig().getMessage("permissions.removed") + ": " + permission + " for " + player.getName());
            }
        }

        player.setOp(false);

        plugin.getLuckPerms().getUserManager().saveUser(user);
    }

    private void restorePlayerState(Player player, SessionData session) {
        PlayerState state = session.getSavedState();
        if (state == null) {
            plugin.getLogger().warning("Cannot restore player state for " + player.getName() + " - saved state is null!");
            return;
        }
        plugin.getLogger().info("Restoring player state for " + player.getName());
        state.restore(player);
        plugin.getLogger().info("Successfully restored player state for " + player.getName());
    }

    private void startTimer(Player player, SessionData session) {
        long maxDurationTicks = plugin.getPluginConfig().getMaxDuration() * 60L * 20L;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateBossBar(player, session);

            long maxDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = session.getTotalAllowedMinutes(maxDuration);
            if (session.getDuration().toMinutes() >= totalAllowedMinutes) {
                endSession(player);
            }
        }, 0L, 20L);

        sessionTimers.put(player.getUniqueId(), task);
    }

    private void createBossBar(Player player, DoubleLifeMode mode) {
        String titleKey = mode == DoubleLifeMode.TURBO ? "bossbar.turbo-active-title" : "bossbar.default-active-title";
        BossBar.Color color = mode == DoubleLifeMode.TURBO ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        
        BossBar bossBar = BossBar.bossBar(
                ComponentUtil.gradient(plugin.getLangConfig().getMessage(titleKey), "#FFD700", "#FF6B35"),
                1.0f,
                color,
                BossBar.Overlay.PROGRESS
        );

        player.showBossBar(bossBar);
        bossBars.put(player.getUniqueId(), bossBar);
    }

    private void updateBossBar(Player player, SessionData session) {
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) return;

        Duration duration = session.getDuration();
        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long totalAllowedMinutes = session.getTotalAllowedMinutes(baseDuration);
        float progress = 1.0f - (float) duration.toMinutes() / totalAllowedMinutes;

        long remainingMinutes = totalAllowedMinutes - duration.toMinutes();
        String titleText = plugin.getLangConfig().getMessage("bossbar.remaining-time", remainingMinutes);
        Component title = ComponentUtil.gradient(titleText, "#FFD700", "#FF6B35");

        bossBar.name(title);
        bossBar.progress(Math.max(0, Math.min(1, progress)));

        if (remainingMinutes <= 1) {
            bossBar.color(BossBar.Color.RED);
        } else if (remainingMinutes <= 5) {
            bossBar.color(BossBar.Color.PINK);
        }
    }

    private void executeEntryCommands(Player player) {
        List<String> commands = plugin.getPluginConfig().getEntryCommands();
        for (String command : commands) {
            String processedCommand = command.replace("{player}", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
        }
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public SessionData getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public long getRemainingCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    public boolean prolongSession(Player player, int additionalMinutes) {
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long currentTotalAllowed = session.getTotalAllowedMinutes(baseDuration);
        long newTotalAllowed = currentTotalAllowed + additionalMinutes;

        if (newTotalAllowed > baseDuration * 2) {
            return false;
        }

        long extensionMillis = additionalMinutes * 60L * 1000L;
        session.extendSession(extensionMillis);

        updateBossBar(player, session);

        player.sendMessage(ComponentUtil.success(
            plugin.getLangConfig().getMessage("session.extended-success", player, additionalMinutes)
        ));
        plugin.getLogger().info("Extended session for " + player.getName() + " by " + additionalMinutes + " minutes");

        return true;
    }
}