package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final DoubleLife plugin;

    public PlayerJoinListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check op whitelist on join (delayed so the player object is fully initialised)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.getOpGuardService().checkAndDeop(player);
            plugin.getSessionManager().restoreSessionForPlayer(player);
        }, 20L);
    }

    // LOWEST: ends the session before the server saves the player and before other plugins read the inventory
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuitDuringSession(PlayerQuitEvent event) {
        plugin.getSessionManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove op on disconnect so it is not persisted in ops.json
        plugin.getOpGuardService().checkAndDeop(event.getPlayer());
    }
}