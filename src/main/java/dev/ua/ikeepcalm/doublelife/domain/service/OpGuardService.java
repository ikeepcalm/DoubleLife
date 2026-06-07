package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Enforces the op whitelist: only players whose names appear in
 * config op-whitelist.allowed-ops may hold operator status.
 *
 * Enforcement runs on join, quit, and every 20 ticks via a repeating task
 * registered in DoubleLife.onEnable.  The /op command is also intercepted
 * in CommandInterceptor.
 */
public class OpGuardService {

    private final DoubleLife plugin;

    public OpGuardService(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getPluginConfig().isOpWhitelistEnabled();
    }

    /** Returns true if this player is allowed to be an operator. */
    public boolean isAllowed(String playerName) {
        return plugin.getPluginConfig().getAllowedOps().contains(playerName.toLowerCase());
    }

    /**
     * Checks the player and removes op if they are not on the whitelist.
     * Safe to call from the main thread.
     */
    public void checkAndDeop(Player player) {
        if (!isEnabled()) return;
        if (!player.isOp()) return;
        if (isAllowed(player.getName())) return;

        player.setOp(false);

        player.sendMessage(ComponentUtil.error(
                "Your operator status has been revoked by DoubleLife's op-whitelist. " +
                "Contact a server owner if this is an error."));

        String warning = "[DoubleLife] Revoked operator status from " + player.getName()
                + " — not on the op-whitelist.";
        plugin.getLogger().warning(warning);

        Component adminAlert = ComponentUtil.warning(
                player.getName() + " had operator status revoked (not on op-whitelist).");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("doublelife.admin")) {
                online.sendMessage(adminAlert);
            }
        }
    }

    /** Called every 20 ticks to sweep all online players. */
    public void checkAllOnlinePlayers() {
        if (!isEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkAndDeop(player);
        }
    }

    /**
     * Validates an /op target before the command executes (called from CommandInterceptor).
     * Returns true if the op attempt should be blocked.
     */
    public boolean shouldBlockOpCommand(String targetName) {
        if (!isEnabled()) return false;
        return !isAllowed(targetName);
    }
}
