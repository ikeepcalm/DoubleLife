package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.luckperms.api.model.user.User;
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

        // Block /op targeting non-whitelisted players
        if (command.is("op") && command.firstArg() != null) {
            String target = command.firstArg();
            if (plugin.getOpGuardService().shouldBlockOpCommand(target)) {
                event.setCancelled(true);
                player.sendMessage(ComponentUtil.error(
                        "Cannot op '" + target + "' — they are not on the DoubleLife op-whitelist."));
                plugin.getLogger().warning("[DoubleLife] " + player.getName()
                        + " attempted to op non-whitelisted player: " + target);
                return;
            }
        }

        SessionData session = plugin.getSessionManager().getSession(player);

        if (session != null) {
            return;
        }

        if (isRestrictedCommand(player, command)) {
            event.setCancelled(true);
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("command.restricted", player)));
            player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("command.doublelife-required", player)));
        }
    }
    
    private boolean isRestrictedCommand(Player player, CommandNames.Parsed command) {
        Map<String, List<String>> groupCommands = plugin.getPluginConfig().getGroupCommands();
        
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return false;
        }
        
        for (String group : groupCommands.keySet()) {
            if (user.getInheritedGroups(user.getQueryOptions()).contains(plugin.getLuckPerms().getGroupManager().getGroup(group))) {
                List<String> restrictedCommands = groupCommands.get(group);
                if (command.matchesAny(restrictedCommands)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}