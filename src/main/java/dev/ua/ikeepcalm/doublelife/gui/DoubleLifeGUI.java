package dev.ua.ikeepcalm.doublelife.gui;

import dev.triumphteam.gui.builder.item.PaperItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DoubleLifeGUI {

    private static final int EXTEND_MINUTES = 5;

    private final DoubleLife plugin;

    public DoubleLifeGUI(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Gui gui = Gui.gui()
                .title(ComponentUtil.gradient(msg("gui.title", player), "#FFD700", "#FF6B35"))
                .rows(3)
                .disableAllInteractions()
                .create();

        SessionData session = plugin.getSessionManager().getSession(player);
        if (session != null) {
            gui.setItem(10, createStatusItem(player, session));
            if (player.hasPermission("doublelife.prolong")) {
                gui.setItem(12, createExtendButton(player, session));
            }
            gui.setItem(14, createLogPreviewItem(player, session));
            gui.setItem(16, commandButton(Material.REDSTONE_BLOCK, msg("gui.end-button", player),
                    plugin.getLangConfig().getMessageList("gui.end-button-lore", player), "doublelife end"));
        } else {
            gui.setItem(11, createModeButton(player, DoubleLifeMode.DEFAULT));
            if (player.hasPermission("doublelife.turbo")) {
                gui.setItem(15, createModeButton(player, DoubleLifeMode.TURBO));
            }
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            gui.setItem(13, cooldown > 0 ? createCooldownItem(player, cooldown) : createInfoItem(player));
        }

        if (player.hasPermission("doublelife.status")) {
            gui.setItem(22, createActiveSessionsItem(player));
        }

        if (player.hasPermission("doublelife.history")) {
            gui.setItem(26, commandButton(Material.KNOWLEDGE_BOOK, msg("gui.history-button", player),
                    plugin.getLangConfig().getMessageList("gui.history-button-lore", player), "doublelife history"));
        }

        gui.getFiller().fill(new GuiItem(PaperItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build()));
        gui.open(player);
    }

    private GuiItem commandButton(Material material, String title, List<String> loreLines, String command) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(ComponentUtil.loreLines(loreLines.toArray(new String[0])));
        lore.add(Component.empty());

        return PaperItemBuilder.from(material)
                .name(ComponentUtil.button(title))
                .lore(lore)
                .asGuiItem(event -> {
                    Player p = (Player) event.getWhoClicked();
                    p.closeInventory();
                    p.performCommand(command);
                });
    }

    private GuiItem createModeButton(Player player, DoubleLifeMode mode) {
        boolean turbo = mode == DoubleLifeMode.TURBO;
        List<String> lore = new ArrayList<>(plugin.getLangConfig().getMessageList(turbo ? "gui.turbo-button-lore" : "gui.default-button-lore", player,
                plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getCooldownDuration()));
        lore.add("");
        lore.add(msg("gui.click-to-start", player));
        return commandButton(turbo ? Material.DIAMOND_BLOCK : Material.EMERALD_BLOCK,
                msg(turbo ? "gui.turbo-button" : "gui.default-button", player), lore, turbo ? "doublelife turbo" : "doublelife start");
    }

    private GuiItem createExtendButton(Player player, SessionData session) {
        long left = plugin.getPluginConfig().getMaxExtension() - session.getExtensionMinutes();
        return commandButton(Material.CLOCK, plugin.getLangConfig().getMessage("gui.extend-button", player, EXTEND_MINUTES),
                plugin.getLangConfig().getMessageList("gui.extend-button-lore", player, Math.max(0, left)), "doublelife extend " + EXTEND_MINUTES);
    }

    private GuiItem createStatusItem(Player player, SessionData session) {
        long remaining = plugin.getSessionManager().getRemainingSeconds(session);
        long total = plugin.getSessionManager().getTotalSeconds(session);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(session.getMode().getDisplayName(), ComponentUtil.ACCENT_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.reason", player,
                session.getReason() != null ? session.getReason() : "-"), ComponentUtil.SECONDARY_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-elapsed", player,
                ComponentUtil.clock(session.getDuration().getSeconds())), ComponentUtil.PRIMARY_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-remaining", player,
                ComponentUtil.clock(remaining)), ComponentUtil.WARNING_COLOR));
        lore.add(ComponentUtil.progressBar((int) remaining, (int) Math.max(1, total), 20));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.activities-logged", player,
                session.getActivities().size()), ComponentUtil.SUCCESS_COLOR));
        lore.add(Component.empty());

        return PaperItemBuilder.from(session.getMode() == DoubleLifeMode.TURBO ? Material.BLAZE_POWDER : Material.COMPASS)
                .name(ComponentUtil.title(msg("gui.status-button", player)))
                .lore(lore)
                .asGuiItem();
    }

    private GuiItem createInfoItem(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        for (String line : plugin.getLangConfig().getMessageList("gui.info-description", player)) {
            if (line.isBlank()) {
                lore.add(Component.empty());
            } else if (line.startsWith("✓")) {
                lore.add(ComponentUtil.status(line.substring(1).trim(), true));
            } else {
                lore.add(ComponentUtil.lore(line));
            }
        }
        lore.add(Component.empty());

        return PaperItemBuilder.from(Material.BOOK)
                .name(ComponentUtil.title(msg("gui.info-title", player)))
                .lore(lore)
                .asGuiItem();
    }

    private GuiItem createCooldownItem(Player player, long seconds) {
        return PaperItemBuilder.from(Material.ICE)
                .name(ComponentUtil.title(msg("gui.cooldown-title", player)))
                .lore(Component.empty(),
                        ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.cooldown-wait", player, ComponentUtil.clock(seconds)), ComponentUtil.ERROR_COLOR),
                        Component.empty())
                .asGuiItem();
    }

    private GuiItem createLogPreviewItem(Player player, SessionData session) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(msg("gui.recent-activities", player)));

        int size = session.getActivities().size();
        for (int i = size - 1; i >= Math.max(0, size - 5); i--) {
            var activity = session.getActivities().get(i);
            String details = activity.getDetails().length() > 40 ? activity.getDetails().substring(0, 40) + "…" : activity.getDetails();
            lore.add(ComponentUtil.lore("• " + activity.getType().getDisplayName() + ": " + details, ComponentUtil.ACCENT_COLOR));
        }
        if (size > 5) {
            lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.more-activities", player, size - 5)));
        }
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(msg("gui.note-hint", player)));

        return PaperItemBuilder.from(Material.WRITABLE_BOOK)
                .name(ComponentUtil.title(msg("gui.activity-log-title", player)))
                .lore(lore)
                .asGuiItem();
    }

    private GuiItem createActiveSessionsItem(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        var sessions = plugin.getSessionManager().getActiveSessions();
        if (sessions.isEmpty()) {
            lore.add(ComponentUtil.lore(msg("list.empty", player)));
        }
        for (SessionData session : sessions) {
            String name = Bukkit.getOfflinePlayer(session.getPlayerId()).getName();
            lore.add(ComponentUtil.status(name + " · " + session.getMode().getDisplayName() + " · "
                    + ComponentUtil.clock(plugin.getSessionManager().getRemainingSeconds(session)), true));
        }
        lore.add(Component.empty());

        return PaperItemBuilder.from(Material.PLAYER_HEAD)
                .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.sessions-title", player, sessions.size())))
                .lore(lore)
                .asGuiItem();
    }

    private String msg(String key, Player player) {
        return plugin.getLangConfig().getMessage(key, player);
    }
}
