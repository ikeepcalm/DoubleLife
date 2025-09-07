package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final DoubleLife plugin;

    public PlayerJoinListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Delay session restoration to ensure player is fully loaded
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getSessionManager().restoreSessionForPlayer(player);
        }, 20L); // 1 second delay
    }
}