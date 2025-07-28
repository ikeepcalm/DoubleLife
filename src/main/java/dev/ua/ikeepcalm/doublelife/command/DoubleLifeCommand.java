package dev.ua.ikeepcalm.doublelife.command;

import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.optional.OptionalArg;
import dev.rollczi.litecommands.annotations.permission.Permission;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeSession;
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
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.already-active")));
            return;
        }

        if (!plugin.getSessionManager().canStartSession(player)) {
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            if (cooldown > 0) {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.on-cooldown", cooldown)));
            } else {
                player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start")));
            }
            return;
        }

        plugin.getSessionManager().startSession(player);
    }

    @Execute(name = "end", aliases = {"stop"})
    @Permission("doublelife.use")
    public void end(@Context Player player) {
        if (!plugin.getSessionManager().hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.no-active-session")));
            return;
        }

        plugin.getSessionManager().endSession(player);
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
            sender.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("status.no-session") + ": " + checkPlayer.getName()));
            return;
        }

        DoubleLifeSession session = plugin.getSessionManager().getSession(checkPlayer);
        Duration duration = session.getDuration();
        long remainingMinutes = plugin.getPluginConfig().getMaxDuration() - duration.toMinutes();

        sender.sendMessage(ComponentUtil.gradient("=== Double Life Status ===", "#FFD700", "#FF6B35"));
        sender.sendMessage(ComponentUtil.info("Player: " + checkPlayer.getName()));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("status.session-duration", duration.toMinutes() + " minutes")));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("status.remaining-time", remainingMinutes + " minutes")));
        sender.sendMessage(ComponentUtil.info("Activities logged: " + session.getActivities().size()));
    }

    @Execute(name = "reload")
    @Permission("doublelife.admin")
    public void reload(@Context CommandSender sender) {
        plugin.reload();
        sender.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("messages.reload-success")));
    }

    @Execute(name = "help")
    public void help(@Context CommandSender sender) {
        sendHelp(sender);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ComponentUtil.gradient(plugin.getLangConfig().getMessage("help.header"), "#FFD700", "#FF6B35"));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("help.gui")));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("help.start")));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("help.end")));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("help.status")));

        if (sender.hasPermission("doublelife.admin")) {
            sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("help.reload")));
        }
    }
}