package dev.ua.ikeepcalm.doublelife.command;

import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.join.Join;
import dev.rollczi.litecommands.annotations.optional.OptionalArg;
import dev.rollczi.litecommands.annotations.permission.Permission;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.service.SessionManager;
import dev.ua.ikeepcalm.doublelife.gui.DoubleLifeGUI;
import dev.ua.ikeepcalm.doublelife.gui.HistoryGUI;
import dev.ua.ikeepcalm.doublelife.gui.ReasonDialog;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Command(name = "doublelife", aliases = {"dl"})
public class DoubleLifeCommand {

    private final DoubleLife plugin;
    private final ReasonDialog dialog;

    public DoubleLifeCommand(DoubleLife plugin) {
        this.plugin = plugin;
        this.dialog = new ReasonDialog(plugin);
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
        askReasonAndStart(player, DoubleLifeMode.DEFAULT);
    }

    @Execute(name = "start")
    @Permission("doublelife.use")
    public void start(@Context Player player, @Join("reason") String reason) {
        tryStart(player, DoubleLifeMode.DEFAULT, reason);
    }

    @Execute(name = "turbo")
    @Permission("doublelife.turbo")
    public void startTurbo(@Context Player player) {
        askReasonAndStart(player, DoubleLifeMode.TURBO);
    }

    @Execute(name = "turbo")
    @Permission("doublelife.turbo")
    public void startTurbo(@Context Player player, @Join("reason") String reason) {
        tryStart(player, DoubleLifeMode.TURBO, reason);
    }

    private void askReasonAndStart(Player player, DoubleLifeMode mode) {
        if (!checkCanStart(player, mode)) return;
        dialog.ask(player, "dialog.start", reason -> {
            if (reason.isEmpty() && plugin.getPluginConfig().isRequireReason()) {
                player.sendMessage(ComponentUtil.error(msg("session.reason-required", player)));
                return;
            }
            tryStart(player, mode, reason);
        });
    }

    private void tryStart(Player player, DoubleLifeMode mode, String reason) {
        if (!checkCanStart(player, mode)) return;
        plugin.getSessionManager().startSession(player, mode, reason == null || reason.isBlank() ? null : reason.trim());
    }

    private boolean checkCanStart(Player player, DoubleLifeMode mode) {
        SessionManager sessions = plugin.getSessionManager();
        if (sessions.hasActiveSession(player)) {
            player.sendMessage(ComponentUtil.error(msg("session.already-active", player)));
            return false;
        }
        if (sessions.canStartSession(player, mode)) {
            return true;
        }
        long cooldown = sessions.getRemainingCooldown(player);
        if (cooldown > 0) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.on-cooldown", player, cooldown)));
        } else if (mode == DoubleLifeMode.TURBO && !player.hasPermission("doublelife.turbo")) {
            player.sendMessage(ComponentUtil.error(msg("session.turbo-permission-required", player)));
        } else {
            player.sendMessage(ComponentUtil.error(msg("session.cannot-start", player)));
        }
        return false;
    }

    @Execute(name = "end", aliases = {"stop"})
    @Permission("doublelife.use")
    public void end(@Context Player player) {
        SessionData session = requireSession(player);
        if (session == null) return;
        // an old dialog confirmed later must not end a newer session
        dialog.ask(player, "dialog.end", summary -> {
            if (plugin.getSessionManager().getSession(player) == session) endWithSummary(player, session, summary);
        });
    }

    @Execute(name = "end", aliases = {"stop"})
    @Permission("doublelife.use")
    public void end(@Context Player player, @Join("summary") String summary) {
        SessionData session = requireSession(player);
        if (session == null) return;
        endWithSummary(player, session, summary);
    }

    private void endWithSummary(Player player, SessionData session, String summary) {
        if (!summary.isBlank()) {
            session.logActivity(ActivityType.NOTE, "End summary: " + summary.trim(), "");
        }
        plugin.getSessionManager().endSession(player);
    }

    @Execute(name = "note")
    @Permission("doublelife.use")
    public void note(@Context Player player, @Join("text") String text) {
        SessionData session = requireSession(player);
        if (session == null) return;
        session.logActivity(ActivityType.NOTE, text, "");
        player.sendMessage(ComponentUtil.success(msg("session.note-added", player)));
    }

    @Execute(name = "prolong", aliases = {"extend"})
    @Permission("doublelife.prolong")
    public void prolong(@Context Player player, @Arg int minutes) {
        if (requireSession(player) == null) return;
        if (minutes <= 0) {
            player.sendMessage(ComponentUtil.error(msg("session.extend-invalid", player)));
            return;
        }
        if (!plugin.getSessionManager().prolongSession(player, minutes)) {
            player.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.extend-limit", player,
                    plugin.getPluginConfig().getMaxExtension())));
        }
    }

    @Execute(name = "forceend")
    @Permission("doublelife.admin")
    public void forceEnd(@Context CommandSender sender, @Arg Player target) {
        if (!plugin.getSessionManager().hasActiveSession(target)) {
            sender.sendMessage(ComponentUtil.warning(msg("status.no-session", sender) + ": " + target.getName()));
            return;
        }
        plugin.getSessionManager().getSession(target).logActivity(ActivityType.NOTE, "Force-ended by " + sender.getName(), "");
        plugin.getSessionManager().endSession(target);
        target.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.force-ended-target", target, sender.getName())));
        sender.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.force-ended", sender, target.getName())));
    }

    @Execute(name = "list")
    @Permission("doublelife.status")
    public void list(@Context CommandSender sender) {
        var sessions = plugin.getSessionManager().getActiveSessions();
        if (sessions.isEmpty()) {
            sender.sendMessage(ComponentUtil.info(msg("list.empty", sender)));
            return;
        }
        sender.sendMessage(ComponentUtil.gradient(msg("list.header", sender), "#FFD700", "#FF6B35"));
        for (SessionData session : sessions) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(session.getPlayerId());
            sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("list.entry", sender,
                    player.getName(), session.getMode().getDisplayName(),
                    ComponentUtil.clock(plugin.getSessionManager().getRemainingSeconds(session)),
                    session.getReason() != null ? session.getReason() : "-")));
        }
    }

    @Execute(name = "history")
    @Permission("doublelife.history")
    public void history(@Context Player player, @OptionalArg String name) {
        new HistoryGUI(plugin).open(player, name);
    }

    @Execute(name = "status")
    @Permission("doublelife.status")
    public void status(@Context CommandSender sender, @OptionalArg Player target) {
        Player checkPlayer = target != null ? target : sender instanceof Player p ? p : null;
        if (checkPlayer == null) {
            sender.sendMessage(ComponentUtil.error("Please specify a player!"));
            return;
        }

        SessionData session = plugin.getSessionManager().getSession(checkPlayer);
        if (session == null) {
            sender.sendMessage(ComponentUtil.warning(msg("status.no-session", sender) + ": " + checkPlayer.getName()));
            return;
        }

        sender.sendMessage(ComponentUtil.gradient("=== Double Life Status ===", "#FFD700", "#FF6B35"));
        sender.sendMessage(ComponentUtil.info("Player: " + checkPlayer.getName()));
        sender.sendMessage(ComponentUtil.info("Mode: " + session.getMode().getDisplayName()));
        sender.sendMessage(ComponentUtil.info("Reason: " + (session.getReason() != null ? session.getReason() : "-")));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("status.session-duration", sender,
                ComponentUtil.clock(session.getDuration().getSeconds()))));
        sender.sendMessage(ComponentUtil.info(plugin.getLangConfig().getMessage("status.remaining-time", sender,
                ComponentUtil.clock(plugin.getSessionManager().getRemainingSeconds(session)))));
        sender.sendMessage(ComponentUtil.info("Activities logged: " + session.getActivities().size()));
    }

    @Execute(name = "reload")
    @Permission("doublelife.admin")
    public void reload(@Context CommandSender sender) {
        plugin.reload();
        sender.sendMessage(ComponentUtil.success(msg("messages.reload-success", sender)));
    }

    @Execute(name = "help")
    public void help(@Context CommandSender sender) {
        sendHelp(sender);
    }

    private SessionData requireSession(Player player) {
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) {
            player.sendMessage(ComponentUtil.error(msg("session.no-active-session", player)));
        }
        return session;
    }

    private String msg(String key, CommandSender sender) {
        return plugin.getLangConfig().getMessage(key, sender);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ComponentUtil.gradient(msg("help.header", sender), "#FFD700", "#FF6B35"));
        for (String key : new String[]{"help.gui", "help.start", "help.turbo", "help.end", "help.note", "help.status", "help.list"}) {
            sender.sendMessage(ComponentUtil.info(msg(key, sender)));
        }
        if (sender.hasPermission("doublelife.prolong")) {
            sender.sendMessage(ComponentUtil.info(msg("help.prolong", sender)));
        }
        if (sender.hasPermission("doublelife.history")) {
            sender.sendMessage(ComponentUtil.info(msg("help.history", sender)));
        }
        if (sender.hasPermission("doublelife.admin")) {
            sender.sendMessage(ComponentUtil.info(msg("help.forceend", sender)));
            sender.sendMessage(ComponentUtil.info(msg("help.reload", sender)));
        }
    }
}
