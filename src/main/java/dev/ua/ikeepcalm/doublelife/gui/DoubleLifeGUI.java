package dev.ua.ikeepcalm.doublelife.gui;

import dev.triumphteam.gui.builder.item.PaperItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class DoubleLifeGUI {
    
    private final DoubleLife plugin;
    
    public DoubleLifeGUI(DoubleLife plugin) {
        this.plugin = plugin;
    }
    
    public void open(Player player) {
        Gui gui = Gui.gui()
            .title(ComponentUtil.gradient(plugin.getLangConfig().getMessage("gui.title", player), "#FFD700", "#FF6B35"))
            .rows(3)
            .create();
        
        gui.setDefaultClickAction(event -> event.setCancelled(true));
        
        boolean hasActiveSession = plugin.getSessionManager().hasActiveSession(player);
        
        if (hasActiveSession) {
            gui.setItem(13, createEndButton(player));
            gui.setItem(11, createStatusItem(player));
            gui.setItem(15, createLogPreviewItem(player));
        } else {
            gui.setItem(11, createModeButton(player, DoubleLifeMode.DEFAULT));
            
            // Only show turbo mode button if player has turbo permission
            if (player.hasPermission("doublelife.turbo")) {
                gui.setItem(15, createModeButton(player, DoubleLifeMode.TURBO));
            }
            
            gui.setItem(22, createInfoItem(player));
            
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            if (cooldown > 0) {
                gui.setItem(13, createCooldownItem(player, cooldown));
            }
        }
        
        gui.getFiller().fill(new GuiItem(Material.GRAY_STAINED_GLASS_PANE));
        
        gui.open(player);
    }

    private GuiItem createModeButton(Player player, DoubleLifeMode mode) {
        Material material = mode == DoubleLifeMode.TURBO ? Material.DIAMOND_BLOCK : Material.EMERALD_BLOCK;
        String titleKey = mode == DoubleLifeMode.TURBO ? "gui.turbo-button" : "gui.default-button";
        String loreKey = mode == DoubleLifeMode.TURBO ? "gui.turbo-button-lore" : "gui.default-button-lore";

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(mode.getDescription()));
        lore.add(Component.empty());
        List<String> loreMessages = plugin.getLangConfig().getMessageList(loreKey, player,
                plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getCooldownDuration());
        lore.addAll(ComponentUtil.loreLines(loreMessages.toArray(new String[0])));
        lore.add(Component.empty());

        ItemStack item = PaperItemBuilder.from(material)
            .name(ComponentUtil.button(plugin.getLangConfig().getMessage(titleKey, player)))
            .lore(lore)
            .build();

        return PaperItemBuilder.from(item).asGuiItem(event -> {
            Player p = (Player) event.getWhoClicked();
            p.closeInventory();

            if (plugin.getSessionManager().canStartSession(p, mode)) {
                plugin.getSessionManager().startSession(p, mode);
            } else {
                p.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start", p)));
            }
        });
    }
    
    private GuiItem createStartButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> loreMessages = plugin.getLangConfig().getMessageList("gui.start-button-lore", player,
                plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getCooldownDuration());
        lore.addAll(ComponentUtil.loreLines(loreMessages.toArray(new String[0])));
        lore.add(Component.empty());
        
        ItemStack item = PaperItemBuilder.from(Material.EMERALD_BLOCK)
            .name(ComponentUtil.button(plugin.getLangConfig().getMessage("gui.start-button", player)))
            .lore(lore)
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem(event -> {
            Player p = (Player) event.getWhoClicked();
            p.closeInventory();
            
            if (plugin.getSessionManager().canStartSession(p)) {
                plugin.getSessionManager().startSession(p);
            } else {
                p.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start", p)));
            }
        });
    }
    
    private GuiItem createEndButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> loreMessages = plugin.getLangConfig().getMessageList("gui.end-button-lore", player);
        lore.addAll(ComponentUtil.loreLines(loreMessages.toArray(new String[0])));
        lore.add(Component.empty());
        
        ItemStack item = PaperItemBuilder.from(Material.REDSTONE_BLOCK)
            .name(ComponentUtil.button(plugin.getLangConfig().getMessage("gui.end-button", player)))
            .lore(lore)
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem(event -> {
            Player p = (Player) event.getWhoClicked();
            p.closeInventory();
            plugin.getSessionManager().endSession(p);
        });
    }
    
    private GuiItem createStatusItem(Player player) {
        SessionData session = plugin.getSessionManager().getSession(player);
        Duration duration = session.getDuration();
        long remainingMinutes = plugin.getPluginConfig().getMaxDuration() - duration.toMinutes();
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore("Mode: " + session.getMode().getDisplayName(), ComponentUtil.ACCENT_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-elapsed", player, duration.toMinutes()), ComponentUtil.PRIMARY_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-remaining", player, remainingMinutes), ComponentUtil.WARNING_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.activities-logged", player, session.getActivities().size()), ComponentUtil.SUCCESS_COLOR));
        lore.add(Component.empty());
        
        ItemStack item = PaperItemBuilder.from(Material.CLOCK)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.status-button", player)))
            .lore(lore)
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createInfoItem(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> infoLines = plugin.getLangConfig().getMessageList("gui.info-description", player);
        for (String line : infoLines) {
            if (line.trim().isEmpty()) {
                lore.add(Component.empty());
            } else if (line.startsWith("✓")) {
                lore.add(ComponentUtil.status(line.substring(2).trim(), true));
            } else {
                lore.add(ComponentUtil.lore(line));
            }
        }
        lore.add(Component.empty());
        
        ItemStack item = PaperItemBuilder.from(Material.BOOK)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.info-title", player)))
            .lore(lore)
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createCooldownItem(Player player, long seconds) {
        ItemStack item = PaperItemBuilder.from(Material.ICE)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.cooldown-title", player)))
            .lore(
                Component.empty(),
                ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.cooldown-wait", player, ComponentUtil.duration(seconds)), ComponentUtil.ERROR_COLOR),
                Component.empty()
            )
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createLogPreviewItem(Player player) {
        SessionData session = plugin.getSessionManager().getSession(player);
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.recent-activities", player)));
        
        int count = 0;
        for (int i = session.getActivities().size() - 1; i >= 0 && count < 5; i--) {
            var activity = session.getActivities().get(i);
            lore.add(ComponentUtil.lore("• " + activity.getType().getDisplayName(), ComponentUtil.ACCENT_COLOR));
            count++;
        }
        
        if (session.getActivities().size() > 5) {
            lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.more-activities", player, (session.getActivities().size() - 5))));
        }
        
        lore.add(Component.empty());
        
        ItemStack item = PaperItemBuilder.from(Material.PAPER)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.activity-log-title", player)))
            .lore(lore)
            .build();
        
        return PaperItemBuilder.from(item).asGuiItem();
    }
}