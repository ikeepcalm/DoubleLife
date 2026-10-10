package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import dev.ua.ikeepcalm.doublelife.util.StaffGroups;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Map;

public class CommandInterceptor implements Listener {
    
    private final DoubleLife plugin;
    
    public CommandInterceptor(DoubleLife plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String raw = event.getMessage();
        // Namespace prefixes (minecraft:tp) and aliases resolve to the same names as the plain label.
        CommandNames.Parsed command = CommandNames.parse(raw);
        plugin.getActivityAuditor().noteParsed(raw, command);

        // Block /op targeting non-whitelisted players
        if (command.is("op") && command.firstArg() != null) {
            String target = command.firstArg();
            if (plugin.getOpGuardService().shouldBlockOpCommand(target)) {
                event.setCancelled(true);
                player.sendMessage(ComponentUtil.error(
                        "Cannot op '" + target + "' — they are not on the DoubleLife op-whitelist."));
                plugin.getLogger().warning("[DoubleLife] " + player.getName()
                        + " attempted to op non-whitelisted player: " + target);
                plugin.getSessionAuditor().opBlocked(player, target, raw);
                return;
            }
        }

        SessionData session = plugin.getSessionManager().getSession(player);

        if (session != null) {
            return;
        }

        String group = restrictingGroup(player, command);
        if (group != null) {
            event.setCancelled(true);
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("command.restricted", player)));
            player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("command.doublelife-required", player)));
            plugin.getSessionAuditor().restrictedCommandBlocked(player, command, raw, group);
        }
    }
    
    private String restrictingGroup(Player player, CommandNames.Parsed command) {
        Map<String, List<String>> groupCommands = plugin.getPluginConfig().getGroupCommands();
        for (String group : StaffGroups.of(plugin, player)) {
            if (command.matchesAny(groupCommands.get(group))) {
                return group;
            }
        }
        return null;
    }
}