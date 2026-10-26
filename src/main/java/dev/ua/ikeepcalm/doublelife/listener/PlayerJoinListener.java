package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerJoinListener implements Listener {

    private final DoubleLife plugin;

    public PlayerJoinListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getOpGuardService().checkAndDeop(player);
            plugin.getSessionManager().recoverOnJoin(player);
        }, 20L);
    }

    // LOWEST so the real inventory is back before the server saves player data (covers kicks too)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuitEndSession(PlayerQuitEvent event) {
        plugin.getSessionManager().endSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove op on disconnect so it is not persisted in ops.json
        plugin.getOpGuardService().checkAndDeop(event.getPlayer());
    }

    // Session items must not spill into the world; the real inventory is restored when the session ends
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getSessionManager().hasActiveSession(event.getPlayer())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location pending = plugin.getSessionManager().takePendingTeleport(event.getPlayer());
        if (pending != null) {
            event.setRespawnLocation(pending);
        }
    }
}
