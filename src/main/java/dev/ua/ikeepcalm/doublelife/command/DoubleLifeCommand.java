package dev.ua.ikeepcalm.doublelife.command;

import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.optional.OptionalArg;
import dev.rollczi.litecommands.annotations.permission.Permission;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.gui.DoubleLifeGUI;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

@Command(name = "doublelife", aliases = {"dl"})
public class DoubleLifeCommand {

    private final DoubleLife plugin;

    public DoubleLifeCommand(DoubleLife plugin) {
        this.plugin = plugin;
    }

    @Execute
    @Permission("doublelife.use")
    public void openGui(@Context Player player) {
        if (plugin.getPluginConfig().isGuiEnabled()) {
            new DoubleLifeGUI(plugin).open(player);
        } else {
            sendHelp(player);
        }
    }

    @Execute(name = "start")
    @Permission("doublelife.use")
    public void start(@Context Player player) {
        if (plugin.getSessionManager().hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.already-active", player)));
            return;
        }

        if (!plugin.getSessionManager().canStartSession(player)) {
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            if (cooldown > 0) {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.on-cooldown", player, cooldown)));
            } else {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start", player)));
            }
            return;
        }

        plugin.getSessionManager().startSession(player, DoubleLifeMode.DEFAULT);
    }

    @Execute(name = "turbo")
    @Permission("doublelife.turbo")
    public void startTurbo(@Context Player player) {
        if (plugin.getSessionManager().hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.already-active", player)));
            return;
        }

        if (!plugin.getSessionManager().canStartSession(player, DoubleLifeMode.TURBO)) {
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            if (cooldown > 0) {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.on-cooldown", player, cooldown)));
            } else if (!plugin.getSessionManager().hasTurboPermission(player)) {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.turbo-permission-required", player)));
            } else {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start", player)));
            }
            return;
        }

        plugin.getSessionManager().startSession(player, DoubleLifeMode.TURBO);
    }

    @Execute(name = "end", aliases = {"stop"})
    @Permission("doublelife.use")
    public void end(@Context Player player) {
        if (!plugin.getSessionManager().hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.no-active-session", player)));
            return;
        }

        plugin.getSessionManager().endSession(player);
    }

    @Execute(name = "prolong", aliases = {"extend"})
    @Permission("doublelife.prolong")
    public void prolong(@Context Player player, @Arg int minutes) {
        if (!plugin.getSessionManager().hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.no-active-session", player)));
            return;
        }

        if (minutes <= 0 || minutes > 120) {
            player.sendMessage(ComponentUtil.error("Minutes must be between 1 and 120."));
            return;
        }

        boolean success = plugin.getSessionManager().prolongSession(player, minutes);
        if (!success) {
            player.sendMessage(ComponentUtil.error("Cannot extend session - would exceed safety limits."));
        }
    }

    @Execute(name = "status")
    @Permission("doublelife.status")
    public void status(@Context CommandSender sender, @OptionalArg Player target) {
        Player checkPlayer = target;
        if (checkPlayer == null && sender instanceof Player) {
            checkPlayer = (Player) sender;
        }

        if (checkPlayer == null) {
            sender.sendMessage(ComponentUtil.error("Please specify a player!"));
            return;
        }

        if (!plugin.getSessionManager().hasActiveSession(checkPlayer)) {
            String message = (sender instanceof Player) ? 
                plugin.getLangConfig().getMessage("status.no-session", (Player) sender) : 
                plugin.getLangConfig().getMessage("status.no-session");
            sender.sendMessage(ComponentUtil.warning(message + ": " + checkPlayer.getName()));
            return;
        }

        SessionData session = plugin.getSessionManager().getSession(checkPlayer);
        Duration duration = session.getDuration();
        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long totalAllowedMinutes = session.getTotalAllowedMinutes(baseDuration);
        long remainingMinutes = totalAllowedMinutes - duration.toMinutes();

        sender.sendMessage(ComponentUtil.gradient("=== Double Life Status ===", "#FFD700", "#FF6B35"));
        sender.sendMessage(ComponentUtil.info("Player: " + checkPlayer.getName()));
        sender.sendMessage(ComponentUtil.info("Mode: " + session.getMode().getDisplayName()));
        
        String durationMsg = (sender instanceof Player) ? 
            plugin.getLangConfig().getMessage("status.session-duration", (Player) sender, duration.toMinutes() + " minutes") : 
            plugin.getLangConfig().getMessage("status.session-duration", duration.toMinutes() + " minutes");
        String remainingMsg = (sender instanceof Player) ? 
            plugin.getLangConfig().getMessage("status.remaining-time", (Player) sender, remainingMinutes + " minutes") : 
            plugin.getLangConfig().getMessage("status.remaining-time", remainingMinutes + " minutes");
            
        sender.sendMessage(ComponentUtil.info(durationMsg));
        sender.sendMessage(ComponentUtil.info(remainingMsg));
        sender.sendMessage(ComponentUtil.info("Activities logged: " + session.getActivities().size()));
    }

    @Execute(name = "reload")
    @Permission("doublelife.admin")
    public void reload(@Context CommandSender sender) {
        plugin.reload();
        String message = (sender instanceof Player) ? 
            plugin.getLangConfig().getMessage("messages.reload-success", (Player) sender) : 
            plugin.getLangConfig().getMessage("messages.reload-success");
        sender.sendMessage(ComponentUtil.success(message));
    }

    @Execute(name = "help")
    public void help(@Context CommandSender sender) {
        sendHelp(sender);
    }

    private void sendHelp(CommandSender sender) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        
        sender.sendMessage(ComponentUtil.gradient(
            isPlayer ? plugin.getLangConfig().getMessage("help.header", player) : plugin.getLangConfig().getMessage("help.header"), 
            "#FFD700", "#FF6B35"));
        sender.sendMessage(ComponentUtil.info(
            isPlayer ? plugin.getLangConfig().getMessage("help.gui", player) : plugin.getLangConfig().getMessage("help.gui")));
        sender.sendMessage(ComponentUtil.info(
            isPlayer ? plugin.getLangConfig().getMessage("help.start", player) : plugin.getLangConfig().getMessage("help.start")));
        sender.sendMessage(ComponentUtil.info(
            isPlayer ? plugin.getLangConfig().getMessage("help.turbo", player) : plugin.getLangConfig().getMessage("help.turbo")));
        sender.sendMessage(ComponentUtil.info(
            isPlayer ? plugin.getLangConfig().getMessage("help.end", player) : plugin.getLangConfig().getMessage("help.end")));
        sender.sendMessage(ComponentUtil.info(
            isPlayer ? plugin.getLangConfig().getMessage("help.status", player) : plugin.getLangConfig().getMessage("help.status")));

        if (sender.hasPermission("doublelife.prolong")) {
            sender.sendMessage(ComponentUtil.info(
                isPlayer ? plugin.getLangConfig().getMessage("help.prolong", player) : plugin.getLangConfig().getMessage("help.prolong")));
        }

        if (sender.hasPermission("doublelife.admin")) {
            sender.sendMessage(ComponentUtil.info(
                isPlayer ? plugin.getLangConfig().getMessage("help.reload", player) : plugin.getLangConfig().getMessage("help.reload")));
        }
    }
}