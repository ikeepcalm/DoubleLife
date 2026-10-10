package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class StaffGroups {

    private StaffGroups() {
    }

    /** The configured group-commands groups the player inherits, in config order; empty if LuckPerms has no user. */
    public static List<String> of(DoubleLife plugin, Player player) {
        List<String> matched = new ArrayList<>();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return matched;
        }
        Collection<Group> inherited = user.getInheritedGroups(user.getQueryOptions());
        for (String group : plugin.getPluginConfig().getGroupCommands().keySet()) {
            if (inherited.contains(plugin.getLuckPerms().getGroupManager().getGroup(group))) {
                matched.add(group);
            }
        }
        return matched;
    }
}
