package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, DoubleLifeSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public boolean canStartSession(Player player) {
        if (hasActiveSession(player)) {
            return false;
        }

        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return false;
        }

        return player.hasPermission("doublelife.use");
    }

    public void startSession(Player player) {
        if (!canStartSession(player)) {
            return;
        }

        PlayerState savedState = PlayerState.capture(player);
        DoubleLifeSession session = new DoubleLifeSession(player.getUniqueId(), savedState);
        activeSessions.put(player.getUniqueId(), session);

        player.getInventory().clear();

        applyAdminMode(player);
        startTimer(player, session);
        createBossBar(player);

        executeEntryCommands(player);

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.start-success")));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-started", player.getName()));
    }

    public void endSession(Player player) {
        DoubleLifeSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        session.end();
        restorePlayerState(player, session);
        removeAdminMode(player);

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

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success")));
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

    private void createBossBar(Player player) {
        BossBar bossBar = BossBar.bossBar(
                ComponentUtil.gradient(plugin.getLangConfig().getMessage("bossbar.active-title"), "#FFD700", "#FF6B35"),
                1.0f,
                BossBar.Color.YELLOW,
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