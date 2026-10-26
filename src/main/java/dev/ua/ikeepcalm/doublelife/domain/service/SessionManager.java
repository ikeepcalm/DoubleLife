package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, SessionData> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    // shortcut: in memory only, a restart before the player rejoins loses the pending teleport back
    private final Map<UUID, Location> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> permissionWrites = new ConcurrentHashMap<>();
    private final File backupFolder;

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
        this.backupFolder = new File(plugin.getDataFolder(), "backups");
        backupFolder.mkdirs();
    }

    public boolean canStartSession(Player player, DoubleLifeMode mode) {
        // a leftover backup means a previous restore failed; starting would overwrite the real inventory
        if (hasActiveSession(player) || hasBackup(player.getUniqueId())) return false;
        if (getRemainingCooldown(player) > 0) return false;
        if (!player.hasPermission("doublelife.use")) return false;
        return mode != DoubleLifeMode.TURBO || player.hasPermission("doublelife.turbo");
    }

    public void startSession(Player player, DoubleLifeMode mode, String reason) {
        if (!canStartSession(player, mode)) {
            return;
        }

        SessionData session = new SessionData(player.getUniqueId(), PlayerState.capture(player), mode);
        session.setReason(reason);

        // Never touch the inventory unless the real one is safely on disk first
        if (!writeBackup(session)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.backup-failed", player)));
            return;
        }
        activeSessions.put(player.getUniqueId(), session);

        player.getInventory().clear();
        runCommands(player, plugin.getPluginConfig().getEntryCommands());
        grantPermissions(player.getUniqueId(), permissionsFor(mode));

        if (mode == DoubleLifeMode.TURBO) {
            plugin.getWebhookUtil().sendTurboModeActivation(player.getName(), reason);
        }

        startTimer(player, session);
        createBossBar(player, mode);

        String modeMessage = mode == DoubleLifeMode.TURBO ? "session.turbo-start-success" : "session.default-start-success";
        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage(modeMessage, player)));
        if (reason == null) {
            player.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("session.reason-hint", player)));
        }
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-started", player.getName(), mode.getDisplayName()));
    }

    public void endSession(Player player) {
        SessionData session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.end();

        BukkitTask timer = sessionTimers.remove(player.getUniqueId());
        if (timer != null) timer.cancel();
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) player.hideBossBar(bossBar);

        // Exit commands run while the session's permissions are still in place
        runCommands(player, plugin.getPluginConfig().getExitCommands());
        revokePermissions(player.getUniqueId(), permissionsFor(session.getMode()));
        if (session.getMode() == DoubleLifeMode.TURBO) {
            player.setOp(false);
        }

        if (restoreState(player, session)) {
            deleteBackup(player.getUniqueId());
        }

        plugin.getSessionReporter().report(session, player.getName());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + plugin.getPluginConfig().getCooldownDuration() * 1000L);

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success", player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-ended", player.getName()));
    }

    public void endAllSessions() {
        for (UUID playerId : new HashSet<>(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            try {
                endSession(player);
            } catch (Exception e) {
                // keep going: one failure must not leave the remaining staff in their session inventory
                plugin.getLogger().severe("Failed to end session for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Called on join: a backup with no live session means the server crashed or restore failed mid-session.
     * Give the player their real state back and apply any teleport that could not happen on quit.
     */
    public void recoverOnJoin(Player player) {
        UUID playerId = player.getUniqueId();
        if (!hasActiveSession(player) && hasBackup(playerId)) {
            SessionData session = readBackup(playerId);
            if (session == null) {
                // set aside so the player is not blocked from DL forever; kept on disk for manual recovery
                File broken = new File(backupFolder, playerId + ".broken-" + System.currentTimeMillis() + ".yml");
                backupFile(playerId).renameTo(broken);
                plugin.getLogger().severe("Backup for " + player.getName() + " is unreadable, moved to backups/" + broken.getName() + " for manual recovery");
                return;
            }
            revokePermissions(playerId, permissionsFor(session.getMode()));
            if (session.getMode() == DoubleLifeMode.TURBO) {
                player.setOp(false);
            }
            if (restoreState(player, session)) {
                deleteBackup(playerId);
                // not reported: the activity log lived in memory and is gone, a "clean" report would mislead reviewers
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.recovered", player)));
                plugin.getLogger().warning("Recovered interrupted " + session.getMode().getDisplayName() + " session for " + player.getName()
                        + " (started " + session.getStartTime() + ", reason: " + session.getReason() + "); its activity log was lost");
            }
        }
        // a dead player gets it on respawn instead
        Location pending = player.isDead() ? null : takePendingTeleport(player);
        if (pending != null) {
            player.teleport(pending);
        }
    }

    public Location takePendingTeleport(Player player) {
        return pendingTeleports.remove(player.getUniqueId());
    }

    private boolean restoreState(Player player, SessionData session) {
        if (session.getSavedState() == null) {
            plugin.getLogger().severe("No saved state for " + player.getName() + ", nothing to restore");
            return true;
        }
        try {
            if (!session.getSavedState().restore(player)) {
                pendingTeleports.put(player.getUniqueId(), session.getSavedState().getLocation());
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to restore state for " + player.getName() + ", backup kept for next join: " + e.getMessage());
            return false;
        }
    }

    private List<String> permissionsFor(DoubleLifeMode mode) {
        return mode == DoubleLifeMode.TURBO ? plugin.getPluginConfig().getTemporaryPermissions() : plugin.getPluginConfig().getDefaultPermissions();
    }

    private void grantPermissions(UUID playerId, List<String> permissions) {
        if (permissions.isEmpty()) return;
        // Expire at the longest a session can run, so extensions keep their permissions and a crash still cleans up
        Duration expiry = Duration.ofMinutes(plugin.getPluginConfig().getMaxDuration() + plugin.getPluginConfig().getMaxExtension());
        modifyUserInOrder(playerId, user -> {
            for (String permission : permissions) {
                boolean negated = permission.startsWith("-");
                user.data().add(PermissionNode.builder(negated ? permission.substring(1) : permission)
                        .value(!negated)
                        .expiry(expiry)
                        .build());
            }
        });
    }

    private void revokePermissions(UUID playerId, List<String> permissions) {
        if (permissions.isEmpty()) return;
        Set<String> keys = permissions.stream().map(p -> p.startsWith("-") ? p.substring(1) : p).collect(Collectors.toSet());
        // Only temporary nodes: a permanent node with the same key belongs to the player's real rank
        modifyUserInOrder(playerId, user -> user.data().clear(node -> node.hasExpiry() && keys.contains(node.getKey())));
    }

    // LuckPerms writes are async; chain them per player so a quick start→end can't revoke before the grant is saved
    private void modifyUserInOrder(UUID playerId, Consumer<User> change) {
        permissionWrites.compute(playerId, (id, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> plugin.getLuckPerms().getUserManager().modifyUser(id, change)));
    }

    private void runCommands(Player player, List<String> commands) {
        for (String raw : commands) {
            String command = raw.replace("{player}", player.getName());
            try {
                if (command.startsWith("player:")) {
                    player.performCommand(command.substring("player:".length()).trim());
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Command '" + command + "' failed for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    private File backupFile(UUID playerId) {
        return new File(backupFolder, playerId + ".yml");
    }

    private boolean hasBackup(UUID playerId) {
        return backupFile(playerId).exists();
    }

    private boolean writeBackup(SessionData session) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("session", session);
            yaml.save(backupFile(session.getPlayerId()));
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to write session backup for " + session.getPlayerId() + ": " + e.getMessage());
            return false;
        }
    }

    private SessionData readBackup(UUID playerId) {
        try {
            Object session = YamlConfiguration.loadConfiguration(backupFile(playerId)).get("session");
            return session instanceof SessionData data ? data : null;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to read session backup for " + playerId + ": " + e.getMessage());
            return null;
        }
    }

    private void deleteBackup(UUID playerId) {
        backupFile(playerId).delete();
    }

    private void startTimer(Player player, SessionData session) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long remaining = getRemainingSeconds(session);
            if (remaining <= 0) {
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.max-duration-reached", player)));
                endSession(player);
                return;
            }
            if (remaining == 60) {
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.ending-soon", player)));
            }
            updateBossBar(player, session, remaining);
        }, 0L, 20L);

        sessionTimers.put(player.getUniqueId(), task);
    }

    private void createBossBar(Player player, DoubleLifeMode mode) {
        String titleKey = mode == DoubleLifeMode.TURBO ? "bossbar.turbo-active-title" : "bossbar.default-active-title";
        BossBar.Color color = mode == DoubleLifeMode.TURBO ? BossBar.Color.YELLOW : BossBar.Color.BLUE;

        BossBar bossBar = BossBar.bossBar(
                ComponentUtil.gradient(plugin.getLangConfig().getMessage(titleKey, player), "#FFD700", "#FF6B35"),
                1.0f, color, BossBar.Overlay.PROGRESS);

        player.showBossBar(bossBar);
        bossBars.put(player.getUniqueId(), bossBar);
    }

    private void updateBossBar(Player player, SessionData session, long remainingSeconds) {
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) return;

        long totalSeconds = getTotalSeconds(session);
        String title = plugin.getLangConfig().getMessage("bossbar.remaining-time", player, session.getMode().getDisplayName(), ComponentUtil.clock(remainingSeconds));
        bossBar.name(ComponentUtil.gradient(title, "#FFD700", "#FF6B35"));
        bossBar.progress(Math.clamp((float) remainingSeconds / totalSeconds, 0f, 1f));

        if (remainingSeconds <= 60) {
            bossBar.color(BossBar.Color.RED);
        } else if (remainingSeconds <= 300) {
            bossBar.color(BossBar.Color.PINK);
        }
    }

    public long getTotalSeconds(SessionData session) {
        return session.getTotalAllowedMinutes(plugin.getPluginConfig().getMaxDuration()) * 60;
    }

    public long getRemainingSeconds(SessionData session) {
        return Math.max(0, getTotalSeconds(session) - session.getDuration().getSeconds());
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public SessionData getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public Collection<SessionData> getActiveSessions() {
        return activeSessions.values();
    }

    public long getRemainingCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return 0;
        return Math.max(0, (cooldownEnd - System.currentTimeMillis()) / 1000);
    }

    public boolean prolongSession(Player player, int additionalMinutes) {
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null || session.getExtensionMinutes() + additionalMinutes > plugin.getPluginConfig().getMaxExtension()) {
            return false;
        }

        session.extendSession(additionalMinutes);
        updateBossBar(player, session, getRemainingSeconds(session));
        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.extended-success", player, additionalMinutes)));
        plugin.getLogger().info("Extended session for " + player.getName() + " by " + additionalMinutes + " minutes");
        return true;
    }
}
