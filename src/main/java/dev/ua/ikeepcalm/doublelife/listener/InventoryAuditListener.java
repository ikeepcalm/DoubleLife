package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor.ItemMove;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Audit-only listener for item movement between a session player's inventory and anything
 * else: containers, ender chests (including another player's), invsee views, the creative
 * inventory and inventory drags. Observes at MONITOR with ignoreCancelled, so cancelled
 * menu clicks (plugin GUIs) are not recorded.
 */
public class InventoryAuditListener implements Listener {

    /** Top inventories whose contents return to the player on close or are consumed; never storage. */
    private static final Set<String> TRANSIENT_TYPES = Set.of(
            "CRAFTING", "CREATIVE", "WORKBENCH", "ANVIL", "ENCHANTING", "GRINDSTONE", "STONECUTTER",
            "CARTOGRAPHY", "LOOM", "SMITHING", "SMITHING_NEW", "MERCHANT");

    private final DoubleLife plugin;

    public InventoryAuditListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    private ActivityAuditor auditor() {
        return plugin.getActivityAuditor();
    }

    /** True when the top inventory can keep items after the session: containers, ender chests, other players. */
    static boolean isExternal(Player player, Inventory top) {
        String type = top.getType().name();
        if (TRANSIENT_TYPES.contains(type)) return false;
        if (type.equals("PLAYER")) return ActivityAuditor.otherOwner(player, top.getHolder(false)) != null;
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session != null && isExternal(player, event.getInventory())) {
            auditor().containerOpened(player, session, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        if (session == null) return;
        if (event instanceof InventoryCreativeEvent creative) {
            onCreative(player, session, creative);
            return;
        }
        if (event.getAction().name().equals("CLONE_STACK")) {
            ItemStack clone = event.getCurrentItem();
            record(ItemMove.out(player, session, "creative_spawn", clone, maxStack(clone), player.getLocation()));
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !isExternal(player, top)) return;
        recordContainerClick(event, new ContainerSide(player, session, top), event.getClickedInventory().equals(top));
    }

    private static void recordContainerClick(InventoryClickEvent event, ContainerSide side, boolean clickedTop) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        switch (event.getAction().name()) {
            case "PLACE_ALL", "PLACE_SOME" -> { if (clickedTop) side.put(cursor, amount(cursor)); }
            case "PLACE_ONE" -> { if (clickedTop) side.put(cursor, 1); }
            case "PICKUP_ALL", "PICKUP_SOME" -> { if (clickedTop) side.take(current, amount(current)); }
            case "PICKUP_HALF" -> { if (clickedTop) side.take(current, (amount(current) + 1) / 2); }
            case "PICKUP_ONE" -> { if (clickedTop) side.take(current, 1); }
            case "SWAP_WITH_CURSOR" -> { if (clickedTop) side.swap(cursor, current); }
            case "MOVE_TO_OTHER_INVENTORY" -> side.move(current, clickedTop);
            // Only the stack taken from the container: the hotbar stack put in would need a slot read for the row alone.
            case "HOTBAR_SWAP", "HOTBAR_MOVE_AND_READD" -> { if (clickedTop) side.take(current, amount(current)); }
            case "PICKUP_ALL_INTO_BUNDLE", "PICKUP_SOME_INTO_BUNDLE", "PLACE_FROM_BUNDLE" -> { if (clickedTop) side.put(cursor, amount(cursor)); }
            case "PLACE_ALL_INTO_BUNDLE", "PLACE_SOME_INTO_BUNDLE", "PICKUP_FROM_BUNDLE" -> { if (clickedTop) side.take(current, amount(current)); }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SessionData session = plugin.getSessionManager().getSession(player);
        Inventory top = event.getView().getTopInventory();
        if (session == null || !isExternal(player, top)) return;
        // What the drag placed is the cursor's loss, shared over the dragged slots: the container's part
        // needs no read of what its slots held before (that would compare item meta).
        int slots = event.getNewItems().size();
        int topSlots = 0;
        ItemStack sample = null;
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            if (entry.getKey() < top.getSize()) {
                topSlots++;
                sample = entry.getValue();
            }
        }
        int intoTop = slots == 0 ? 0 : (amount(event.getOldCursor()) - amount(event.getCursor())) * topSlots / slots;
        if (sample != null && intoTop > 0) {
            new ContainerSide(player, session, top).put(sample, intoTop);
        }
    }

    /**
     * Creative inventory slot writes: the cursor item is written into a slot of the player's
     * own inventory. This covers palette spawns, middle-click clones and rearranging.
     */
    private void onCreative(Player player, SessionData session, InventoryCreativeEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor.getType().isAir()) return;
        record(ItemMove.out(player, session, "creative_spawn", cursor, cursor.getAmount(), player.getLocation()));
    }

    private void record(ItemMove move) {
        if (move.material() != null) {
            auditor().item(move);
        }
    }

    private static int amount(ItemStack item) {
        return item == null ? 0 : item.getAmount();
    }

    private static int maxStack(ItemStack item) {
        return item == null ? 0 : item.getMaxStackSize();
    }

    private final class ContainerSide {
        private final Player player;
        private final SessionData session;
        private final String type;
        private final UUID owner;
        private final Location location;

        ContainerSide(Player player, SessionData session, Inventory top) {
            this.player = player;
            this.session = session;
            this.type = top.getType().name();
            this.owner = ActivityAuditor.otherOwner(player, top.getHolder(false));
            this.location = top.getLocation() != null ? top.getLocation().clone() : player.getLocation().clone();
        }

        void put(ItemStack item, int amount) {
            emit("container_put", item, amount);
        }

        void take(ItemStack item, int amount) {
            emit("container_take", item, amount);
        }

        void swap(ItemStack putItem, ItemStack takenItem) {
            put(putItem, amount(putItem));
            take(takenItem, amount(takenItem));
        }

        void move(ItemStack item, boolean fromTop) {
            if (fromTop) take(item, amount(item)); else put(item, amount(item));
        }

        private void emit(String kind, ItemStack item, int amount) {
            if (item == null || item.getType().isAir()) return;
            record(ItemMove.out(player, session, kind, item, amount, location).withContainer(type, owner));
        }
    }
}
