package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
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
        String command = raw.toLowerCase();

        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        String[] parts = command.trim().split("\\s+", 2);
        // strip "namespace:" so /coreprotect:co can't bypass the restriction list
        String baseCommand = parts[0].substring(parts[0].indexOf(':') + 1);
        String normalized = parts.length == 2 ? baseCommand + " " + parts[1] : baseCommand;

        // Block /op targeting non-whitelisted players
        if (baseCommand.equals("op") && parts.length == 2) {
            String target = parts[1].trim();
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

        if (isRestrictedCommand(player, normalized)) {
            event.setCancelled(true);
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("command.restricted", player)));
            player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("command.doublelife-required", player)));
        }
    }
    
    private boolean isRestrictedCommand(Player player, String command) {
        if (plugin.getPluginConfig().getAlwaysAllowedCommands().stream().anyMatch(entry -> matches(command, entry))) {
            return false;
        }
        Map<String, List<String>> groupCommands = plugin.getPluginConfig().getGroupCommands();
        
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return false;
        }
        
        for (String group : groupCommands.keySet()) {
            if (user.getInheritedGroups(user.getQueryOptions()).contains(plugin.getLuckPerms().getGroupManager().getGroup(group))) {
                List<String> restrictedCommands = groupCommands.get(group);
                if (restrictedCommands.stream().anyMatch(entry -> matches(command, entry))) {
                    return true;
                }
            }
        }
        
        return false;
    }

    static boolean matches(String command, String entry) {
        return command.equals(entry) || command.startsWith(entry + " ");
    }
}
