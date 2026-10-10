package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor.ItemMove;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Audit-only listener: observes at MONITOR and never changes the event. Session activity
 * rows are emitted as they happen. Item movement between the player and containers lives in
 * {@link InventoryAuditListener}.
 */
public class AuditListener implements Listener {

    private static final Set<PlayerTeleportEvent.TeleportCause> AUDITED_TELEPORTS = Set.of(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    private final DoubleLife plugin;

    public AuditListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    private ActivityAuditor auditor() {
        return plugin.getActivityAuditor();
    }

    private SessionData session(Player player) {
        return plugin.getSessionManager().getSession(player);
    }

    /** Not ignoreCancelled: blocked commands are recorded as DENIED. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        SessionData session = session(event.getPlayer());
        if (!event.isCancelled()) {
            auditor().noteCommand(event.getPlayer().getUniqueId(), "player",
                    session == null ? null : session.getSessionId(), event.getMessage());
        }
        if (session != null) {
            auditor().command(event.getPlayer(), session, event.getMessage(), event.isCancelled());
        }
    }

    /** Console, RCON and command block commands, so a COMMAND gamemode change can name its dispatcher. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        auditor().noteCommand(null, dispatcher(event.getSender()), null, event.getCommand());
    }

    private static String dispatcher(CommandSender sender) {
        if (sender instanceof RemoteConsoleCommandSender) return "rcon";
        if (sender instanceof ConsoleCommandSender) return "console";
        if (sender instanceof BlockCommandSender) return "command_block";
        if (sender instanceof Entity) return "entity";
        return "other";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session != null || player.hasPermission("doublelife.use")) {
            auditor().gamemode(player, session, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null && AUDITED_TELEPORTS.contains(event.getCause())) {
            auditor().teleport(event.getPlayer(), session, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null) {
            auditor().blocks(event.getPlayer(), session, "place",
                    event.getBlockPlaced().getType().name(), event.getBlockPlaced().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null) {
            auditor().blocks(event.getPlayer(), session, "break",
                    event.getBlock().getType().name(), event.getBlock().getLocation());
        }
    }

    /** Emitted at the event: the handler skips cancelled drops, so the Item entity exists. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session == null) return;
        Item entity = event.getItemDrop();
        ItemStack stack = entity.getItemStack();
        ItemMove move = ItemMove.out(player, session, "drop", stack, stack.getAmount(), player.getLocation().clone())
                .withDelivered(true);
        auditor().item(move);
        auditor().trackDrop(entity.getUniqueId(), move);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        auditor().pickedUp(item.getUniqueId(), event.getEntity().getUniqueId(),
                event.getEntity().getType().name(), item.getLocation());
        if (event.getEntity() instanceof Player player) {
            SessionData session = session(player);
            if (session != null) {
                ItemStack stack = item.getItemStack();
                auditor().item(ItemMove.out(player, session, "pickup", stack, stack.getAmount(), item.getLocation()).asIn());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        Location location = event.getInventory().getLocation() != null ? event.getInventory().getLocation() : item.getLocation();
        auditor().pickedUp(item.getUniqueId(), null, event.getInventory().getType().name(), location);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session == null) return;
        Location location = event.getRightClicked().getLocation();
        String type = event.getRightClicked().getType().name();
        ItemStack given = event.getPlayerItem();
        ItemStack taken = event.getArmorStandItem();
        if (!given.getType().isAir()) {
            auditor().item(ItemMove.out(player, session, "armor_stand_put", given, given.getAmount(), location)
                    .withContainer(type, null));
        }
        if (!taken.getType().isAir()) {
            auditor().item(ItemMove.out(player, session, "armor_stand_take", taken, taken.getAmount(), location)
                    .withContainer(type, null).asIn());
        }
    }
}
