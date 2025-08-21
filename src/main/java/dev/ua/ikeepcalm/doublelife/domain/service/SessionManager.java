package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeSession;
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
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, DoubleLifeSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    private static final String SESSION_FILE = "sessions.json";
    private final Gson gson = new Gson();

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
        loadSessionsOnStartup();
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
        DoubleLifeSession session = new DoubleLifeSession(player.getUniqueId(), savedState, mode);
        activeSessions.put(player.getUniqueId(), session);

        if (mode == DoubleLifeMode.TURBO) {
            player.getInventory().clear();
            applyAdminMode(player);
            executeEntryCommands(player);
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
        DoubleLifeSession session = activeSessions.remove(player.getUniqueId());
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

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File sessionFile = new File(dataFolder, SESSION_FILE);
        try (FileWriter writer = new FileWriter(sessionFile)) {
            Map<String, Object> sessionData = new HashMap<>();
            for (Map.Entry<UUID, DoubleLifeSession> entry : activeSessions.entrySet()) {
                DoubleLifeSession session = entry.getValue();
                Map<String, Object> data = new HashMap<>();
                data.put("mode", session.getMode().name());
                data.put("savedState", session.getSavedState());
                data.put("startTime", session.getStartTime().toString());
                sessionData.put(entry.getKey().toString(), data);
            }
            gson.toJson(sessionData, writer);
            plugin.getLogger().info("Saved " + activeSessions.size() + " active sessions to disk");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save sessions: " + e.getMessage());
        }
    }

    private void loadSessionsOnStartup() {
        File sessionFile = new File(plugin.getDataFolder(), SESSION_FILE);
        if (!sessionFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(sessionFile)) {
            TypeToken<Map<String, Map<String, Object>>> typeToken = new TypeToken<Map<String, Map<String, Object>>>() {};
            Map<String, Map<String, Object>> sessionData = gson.fromJson(reader, typeToken.getType());
            
            if (sessionData != null) {
                for (Map.Entry<String, Map<String, Object>> entry : sessionData.entrySet()) {
                    UUID playerId = UUID.fromString(entry.getKey());
                    Player player = Bukkit.getPlayer(playerId);
                    
                    if (player != null) {
                        Map<String, Object> data = entry.getValue();
                        DoubleLifeMode mode = DoubleLifeMode.valueOf((String) data.get("mode"));
                        
                        // Restore the player's saved state
                        Object savedStateObj = data.get("savedState");
                        if (savedStateObj instanceof Map) {
                            // Convert the saved state back to PlayerState object
                            // This is a simplified version - you may need to implement proper deserialization
                            plugin.getLogger().info("Restoring session for " + player.getName() + " in " + mode.getDisplayName());
                            player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.restored-after-restart", player)));
                        }
                    }
                }
                plugin.getLogger().info("Restored sessions for " + sessionData.size() + " players after restart");
            }

            // Delete the session file after loading
            sessionFile.delete();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load sessions: " + e.getMessage());
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

    private void restorePlayerState(Player player, DoubleLifeSession session) {
        PlayerState state = session.getSavedState();
        state.restore(player);
    }

    private void startTimer(Player player, DoubleLifeSession session) {
        long maxDurationTicks = plugin.getPluginConfig().getMaxDuration() * 60L * 20L;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateBossBar(player, session);

            if (session.getDuration().toMinutes() >= plugin.getPluginConfig().getMaxDuration()) {
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

    private void updateBossBar(Player player, DoubleLifeSession session) {
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) return;

        Duration duration = session.getDuration();
        long maxMinutes = plugin.getPluginConfig().getMaxDuration();
        float progress = 1.0f - (float) duration.toMinutes() / maxMinutes;

        long remainingMinutes = maxMinutes - duration.toMinutes();
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

    public DoubleLifeSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public long getRemainingCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }
}