package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityListener implements Listener {
    
    private final DoubleLife plugin;
    private final Map<UUID, List<Block>> blockBatch = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBatchTime = new ConcurrentHashMap<>();
    private static final long BATCH_INTERVAL = 1000;
    
    public ActivityListener(DoubleLife plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogCommands()) return;
        
        String command = event.getMessage();
        String location = formatLocation(player.getLocation());
        session.logActivity(ActivityType.COMMAND, command, location);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogGamemodeChanges()) return;
        
        String details = event.getCause() + ": " + player.getGameMode() + " -> " + event.getNewGameMode();
        String location = formatLocation(player.getLocation());
        session.logActivity(ActivityType.GAMEMODE_CHANGE, details, location);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getPluginConfig().isLogBlockPlacements()) return;
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) return;
        
        addBlockToBatch(player, event.getBlock(), true);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getPluginConfig().isLogBlockPlacements()) return;
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) return;
        
        addBlockToBatch(player, event.getBlock(), false);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogContainerAccess()) return;
        
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof Container) {
            String details = inv.getType().name() + " (" + inv.getSize() + " slots)";
            String location = formatLocation(inv.getLocation());
            session.logActivity(ActivityType.CONTAINER_ACCESS, details, location);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogContainerAccess()) return;
        
        if (event.getClickedInventory() == null || event.getCurrentItem() == null) return;
        
        boolean isContainerTransfer = event.getClickedInventory().getHolder() instanceof Container ||
                                    (event.getClick().isShiftClick() && event.getInventory().getHolder() instanceof Container);
        
        if (isContainerTransfer) {
            ItemStack item = event.getCurrentItem();
            String details = item.getType() + " x" + item.getAmount();
            String location = formatLocation(player.getLocation());
            session.logActivity(ActivityType.CONTAINER_TRANSFER, details, location);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogItemDrops()) return;
        
        ItemStack item = event.getItemDrop().getItemStack();
        String details = item.getType() + " x" + item.getAmount();
        String location = formatLocation(player.getLocation());
        session.logActivity(ActivityType.ITEM_DROP, details, location);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null || !plugin.getPluginConfig().isLogItemDrops()) return;
        
        ItemStack item = event.getItem().getItemStack();
        String details = item.getType() + " x" + item.getAmount();
        String location = formatLocation(player.getLocation());
        session.logActivity(ActivityType.ITEM_PICKUP, details, location);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) return;
        
        String fromLoc = formatLocation(event.getFrom());
        String toLoc = formatLocation(event.getTo());
        String details = event.getCause() + ": " + fromLoc + " -> " + toLoc;
        session.logActivity(ActivityType.TELEPORT, details, toLoc);
    }
    
    private void addBlockToBatch(Player player, Block block, boolean isPlace) {
        UUID playerId = player.getUniqueId();
        List<Block> batch = blockBatch.computeIfAbsent(playerId, k -> new ArrayList<>());
        batch.add(block);
        
        Long lastBatch = lastBatchTime.get(playerId);
        long now = System.currentTimeMillis();
        
        if (lastBatch == null || now - lastBatch >= BATCH_INTERVAL || batch.size() >= 10) {
            processBatch(player, new ArrayList<>(batch), isPlace);
            batch.clear();
            lastBatchTime.put(playerId, now);
        }
    }
    
    private void processBatch(Player player, List<Block> blocks, boolean isPlace) {
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) return;
        
        Map<Material, Integer> counts = new HashMap<>();
        Location firstLoc = null;
        
        for (Block block : blocks) {
            counts.merge(block.getType(), 1, Integer::sum);
            if (firstLoc == null) firstLoc = block.getLocation();
        }
        
        StringBuilder details = new StringBuilder();
        counts.forEach((mat, count) -> {
            if (!details.isEmpty()) details.append(", ");
            details.append(mat.name()).append(" x").append(count);
        });
        
        String location = formatLocation(firstLoc);
        ActivityType type = isPlace ? ActivityType.BLOCK_PLACE : ActivityType.BLOCK_BREAK;
        session.logActivity(type, details.toString(), location);
    }
    
    private String formatLocation(Location loc) {
        if (loc == null) return "Unknown";
        return String.format("%s [%d, %d, %d]", 
            loc.getWorld().getName(), 
            loc.getBlockX(), 
            loc.getBlockY(), 
            loc.getBlockZ());
    }
}