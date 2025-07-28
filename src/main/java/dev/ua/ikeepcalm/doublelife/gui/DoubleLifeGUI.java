package dev.ua.ikeepcalm.doublelife.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.DoubleLifeSession;
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
            .title(ComponentUtil.gradient(plugin.getLangConfig().getMessage("gui.title"), "#FFD700", "#FF6B35"))
            .rows(3)
            .create();
        
        gui.setDefaultClickAction(event -> event.setCancelled(true));
        
        boolean hasActiveSession = plugin.getSessionManager().hasActiveSession(player);
        
        if (hasActiveSession) {
            gui.setItem(13, createEndButton(player));
            gui.setItem(11, createStatusItem(player));
            gui.setItem(15, createLogPreviewItem(player));
        } else {
            gui.setItem(13, createStartButton(player));
            gui.setItem(11, createInfoItem());
            
            long cooldown = plugin.getSessionManager().getRemainingCooldown(player);
            if (cooldown > 0) {
                gui.setItem(15, createCooldownItem(cooldown));
            }
        }
        
        gui.getFiller().fill(new GuiItem(Material.GRAY_STAINED_GLASS_PANE));
        
        gui.open(player);
    }
    
    private GuiItem createStartButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> loreMessages = plugin.getLangConfig().getMessageList("gui.start-button-lore",
                plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getCooldownDuration());
        lore.addAll(ComponentUtil.loreLines(loreMessages.toArray(new String[0])));
        lore.add(Component.empty());
        
        ItemStack item = ItemBuilder.from(Material.EMERALD_BLOCK)
            .name(ComponentUtil.button(plugin.getLangConfig().getMessage("gui.start-button")))
            .lore(lore)
            .build();
        
        return ItemBuilder.from(item).asGuiItem(event -> {
            Player p = (Player) event.getWhoClicked();
            p.closeInventory();
            
            if (plugin.getSessionManager().canStartSession(p)) {
                plugin.getSessionManager().startSession(p);
            } else {
                p.sendMessage(ComponentUtil.error(plugin.getLangConfig().getMessage("session.cannot-start")));
            }
        });
    }
    
    private GuiItem createEndButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> loreMessages = plugin.getLangConfig().getMessageList("gui.end-button-lore");
        lore.addAll(ComponentUtil.loreLines(loreMessages.toArray(new String[0])));
        lore.add(Component.empty());
        
        ItemStack item = ItemBuilder.from(Material.REDSTONE_BLOCK)
            .name(ComponentUtil.button(plugin.getLangConfig().getMessage("gui.end-button")))
            .lore(lore)
            .build();
        
        return ItemBuilder.from(item).asGuiItem(event -> {
            Player p = (Player) event.getWhoClicked();
            p.closeInventory();
            plugin.getSessionManager().endSession(p);
        });
    }
    
    private GuiItem createStatusItem(Player player) {
        DoubleLifeSession session = plugin.getSessionManager().getSession(player);
        Duration duration = session.getDuration();
        long remainingMinutes = plugin.getPluginConfig().getMaxDuration() - duration.toMinutes();
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-elapsed", duration.toMinutes()), ComponentUtil.PRIMARY_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.time-remaining", remainingMinutes), ComponentUtil.WARNING_COLOR));
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.activities-logged", session.getActivities().size()), ComponentUtil.SUCCESS_COLOR));
        lore.add(Component.empty());
        
        ItemStack item = ItemBuilder.from(Material.CLOCK)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.status-button")))
            .lore(lore)
            .build();
        
        return ItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createInfoItem() {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<String> infoLines = plugin.getLangConfig().getMessageList("gui.info-description");
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
        
        ItemStack item = ItemBuilder.from(Material.BOOK)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.info-title")))
            .lore(lore)
            .build();
        
        return ItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createCooldownItem(long seconds) {
        ItemStack item = ItemBuilder.from(Material.ICE)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.cooldown-title")))
            .lore(
                Component.empty(),
                ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.cooldown-wait", ComponentUtil.duration(seconds)), ComponentUtil.ERROR_COLOR),
                Component.empty()
            )
            .build();
        
        return ItemBuilder.from(item).asGuiItem();
    }
    
    private GuiItem createLogPreviewItem(Player player) {
        DoubleLifeSession session = plugin.getSessionManager().getSession(player);
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.recent-activities")));
        
        int count = 0;
        for (int i = session.getActivities().size() - 1; i >= 0 && count < 5; i--) {
            var activity = session.getActivities().get(i);
            lore.add(ComponentUtil.lore("• " + activity.getType().getDisplayName(), ComponentUtil.ACCENT_COLOR));
            count++;
        }
        
        if (session.getActivities().size() > 5) {
            lore.add(ComponentUtil.lore(plugin.getLangConfig().getMessage("gui.more-activities", (session.getActivities().size() - 5))));
        }
        
        lore.add(Component.empty());
        
        ItemStack item = ItemBuilder.from(Material.PAPER)
            .name(ComponentUtil.title(plugin.getLangConfig().getMessage("gui.activity-log-title")))
            .lore(lore)
            .build();
        
        return ItemBuilder.from(item).asGuiItem();
    }
}