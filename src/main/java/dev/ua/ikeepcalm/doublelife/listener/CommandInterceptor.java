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
        String command = event.getMessage().toLowerCase();
        
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        
        String baseCommand = command.split(" ")[0];
        
        SessionData session = plugin.getSessionManager().getSession(player);
        
        if (session != null) {
            return;
        }
        
        if (isRestrictedCommand(player, baseCommand)) {
            event.setCancelled(true);
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("command.restricted", player)));
            player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("command.doublelife-required", player)));
        }
    }
    
    private boolean isRestrictedCommand(Player player, String command) {
        Map<String, List<String>> groupCommands = plugin.getPluginConfig().getGroupCommands();
        
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return false;
        }
        
        for (String group : groupCommands.keySet()) {
            if (user.getInheritedGroups(user.getQueryOptions()).contains(plugin.getLuckPerms().getGroupManager().getGroup(group))) {
                List<String> restrictedCommands = groupCommands.get(group);
                if (restrictedCommands.contains(command)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}