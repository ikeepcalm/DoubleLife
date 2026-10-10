package dev.ua.ikeepcalm.doublelife.audit;

import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Emits in-session activity rows (commands, item movement, gamemode, teleports, containers,
 * blocks) the moment they happen, so a crash or {@code /stop} cannot erase them.
 * Main thread only, except {@link #flushStale()}, which runs on an async timer.
 */
public final class ActivityAuditor {

    public static final String ITEM_OUT = "doublelife.staff.admin_mode.item_out";
    public static final String ITEM_IN = "doublelife.staff.admin_mode.item_in";
    public static final String GAMEMODE = "doublelife.staff.gamemode.changed";
    public static final String TELEPORT = "doublelife.staff.admin_mode.teleport";
    public static final String CONTAINER_OPENED = "doublelife.staff.admin_mode.container_opened";
    public static final String BLOCKS = "doublelife.staff.admin_mode.blocks";

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "give", "i", "item", "lp", "luckperms", "perm", "perms", "permission", "permissions",
            "op", "deop", "co", "coreprotect", "core", "tp", "teleport", "tphere", "tpall", "gamemode", "gm",
            "gmc", "gms", "gmsp", "stop", "restart", "reload", "rl", "plugman", "sudo", "eco", "economy",
            "invsee", "ec", "enderchest", "enchant", "effect", "whitelist", "ban", "pardon", "execute", "coi");
    private static final Set<String> CHAT_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "r", "reply", "m", "t", "pm", "me", "say", "mail", "helpop", "ac");
    private static final int MAX_TRACKED_DROPS = 2_048;

    private final AuditEmitter emitter;
    private final Supplier<List<Pattern>> sensitivePatterns;
    private final ItemBatcher batcher;
    /** Dropped Item entity UUID to the session drop it came from, so pickups by others are attributed. */
    private final Map<UUID, ItemMove> trackedDrops = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ItemMove> eldest) {
            return size() > MAX_TRACKED_DROPS;
        }
    };
    /** Player UUID to the DoubleLife-internal operation currently changing that player's state. */
    private final Map<UUID, String> systemContext = new ConcurrentHashMap<>();
    /** The most recent command dispatched by a player or the console; matched to COMMAND gamemode changes. */
    private CommandSource lastCommand;
    /** The parse CommandInterceptor made for the command being dispatched, so its row does not read the command map again. */
    private volatile ParsedCommand parsedCommand;

    public ActivityAuditor(AuditEmitter emitter, Supplier<List<Pattern>> sensitivePatterns) {
        this.emitter = emitter;
        this.sensitivePatterns = sensitivePatterns;
        this.batcher = new ItemBatcher(this::emitBatch);
    }

    public void withSystemContext(Player player, String context, Runnable action) {
        systemContext.put(player.getUniqueId(), context);
        try {
            action.run();
        } finally {
            systemContext.remove(player.getUniqueId());
        }
    }

    public void flushStale() {
        emitter.guarded(batcher::flushStale);
    }

    /** Emits every pending batch for the session; called before the exited row. */
    public void flushSession(SessionData session) {
        batcher.flushSession(session.getSessionId());
    }

    public void flushAll() {
        batcher.flushAll();
    }

    /**
     * Remembers who issued the command being dispatched. Bukkit does not tell a
     * {@link PlayerGameModeChangeEvent} with cause COMMAND who ran the command, but the command
     * runs in the same tick right after this is recorded, so the change is attributed to it.
     * The message is held only until the next command and its label is split out only when a
     * gamemode row needs it; the arguments are never written to a row.
     */
    public void noteCommand(UUID sourceId, String dispatcher, UUID sourceSessionId, String message) {
        lastCommand = new CommandSource(sourceId, dispatcher, sourceSessionId, message, Bukkit.getCurrentTick());
    }

    /** Called by the command interceptor with the parse it already made for the same event. */
    public void noteParsed(String raw, CommandNames.Parsed parsed) {
        parsedCommand = new ParsedCommand(raw, parsed);
    }

    /** The interceptor's parse of {@code raw}, else the typed label alone (no command map read). */
    private CommandNames.Parsed parsedFor(String raw) {
        ParsedCommand noted = parsedCommand;
        return noted != null && noted.raw().equals(raw) ? noted.parsed() : CommandNames.typed(raw);
    }

    private CommandSource currentCommand() {
        CommandSource source = lastCommand;
        return source != null && source.tick() == Bukkit.getCurrentTick() ? source : null;
    }

    private record CommandSource(UUID sourceId, String dispatcher, UUID sessionId, String message, int tick) {
    }

    private record ParsedCommand(String raw, CommandNames.Parsed parsed) {
    }

    public void command(Player player, SessionData session, String raw, boolean cancelled) {
        emitter.guarded(() -> {
            CommandNames.Parsed parsed = parsedFor(raw);
            boolean sensitive = isSensitive(parsed, raw);
            boolean chat = parsed.names().stream().anyMatch(CHAT_COMMANDS::contains);
            emitter.emit(SessionAuditor.sessionRow(SessionAuditor.ACTION, player, session)
                    .outcome(cancelled ? AuditOutcome.DENIED : AuditOutcome.ATTEMPTED)
                    .risk(sensitive ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .privacy(chat ? AuditPrivacy.CHAT_CONTENT : AuditPrivacy.STAFF_RESTRICTED)
                    .reason(cancelled ? "cancelled" : null)
                    .put("kind", "command")
                    .put("dispatcher", "player")
                    .put("command", raw)
                    .put("command_name", parsed.label())
                    .put("cancelled", cancelled)
                    .put("sensitive", sensitive)
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
            if (!cancelled && parsed.is("give") && givesToOther(player, parsed)) {
                giveToOther(player, session, parsed, raw);
            }
        });
    }

    /** /give naming someone other than the issuer, or a selector other than @s; the typed name is not looked up. */
    private static boolean givesToOther(Player player, CommandNames.Parsed parsed) {
        String first = parsed.firstArg();
        return first != null && !first.equalsIgnoreCase(player.getName()) && !first.startsWith("@s");
    }

    private void giveToOther(Player player, SessionData session, CommandNames.Parsed parsed, String raw) {
        emitter.emit(SessionAuditor.sessionRow(ITEM_OUT, player, session)
                .outcome(AuditOutcome.ATTEMPTED)
                .risk(AuditRisk.HIGH)
                .put("kind", "give_command")
                .put("target_arg", parsed.firstArg())
                .put("command", raw)
                .put("material", parsed.args().size() > 1 ? parsed.args().get(1) : null)
                .put("amount", parsed.args().size() > 2 ? parsed.args().get(2) : "1")
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    private boolean isSensitive(CommandNames.Parsed parsed, String raw) {
        if (parsed.names().stream().anyMatch(SENSITIVE_COMMANDS::contains)) {
            return true;
        }
        for (Pattern pattern : sensitivePatterns.get()) {
            if (pattern.matcher(raw).find()) return true;
        }
        return false;
    }

    /** Every item move is folded into the 10 s batch row: telling a tracked item apart would read its item meta. */
    public void item(ItemMove move) {
        emitter.guarded(() -> {
            if (move.material() == null) {
                return;
            }
            batcher.add(batchKey(move), move.material(), move.amount(), move.location(), move.delivered());
        });
    }

    private static ItemBatcher.BatchKey batchKey(ItemMove move) {
        Location location = move.location();
        String containerKey = move.containerType() == null || location == null || location.getWorld() == null ? ""
                : location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
        return new ItemBatcher.BatchKey(move.eventType(), move.session().getSessionId(), move.player().getUniqueId(),
                move.session().getMode().name(), move.kind(), move.containerType(), containerKey, move.ownerId());
    }

    public void trackDrop(UUID itemEntityId, ItemMove move) {
        trackedDrops.put(itemEntityId, move);
    }

    public void pickedUp(UUID itemEntityId, UUID receiverId, String receiverKind, Location location) {
        emitter.guarded(() -> {
            ItemMove drop = trackedDrops.remove(itemEntityId);
            if (drop == null || drop.player().getUniqueId().equals(receiverId)) {
                return;
            }
            emitter.emit(SessionAuditor.sessionRow(ITEM_OUT, drop.player(), drop.session())
                    .risk(AuditRisk.HIGH)
                    .target(receiverId)
                    .put("kind", "drop_picked_up")
                    .put("receiver_kind", receiverKind)
                    .put("material", drop.material())
                    .put("amount", drop.amount())
                    .putAll(AuditValues.location(location))
                    .build());
        });
    }

    public void blocks(Player player, SessionData session, String kind, String material, Location location) {
        emitter.guarded(() -> {
            batcher.add(new ItemBatcher.BatchKey(BLOCKS, session.getSessionId(), player.getUniqueId(),
                    session.getMode().name(), kind, null, "", null), material, 1, location, null);
        });
    }

    private void emitBatch(ItemBatcher.Batch batch) {
        ItemBatcher.BatchKey key = batch.key;
        AuditEmitter.Builder row = AuditEmitter.row(key.eventType())
                .actor(key.actorId())
                .correlation(key.sessionId())
                .business(key.sessionId().toString())
                .risk(key.eventType().equals(ITEM_OUT) ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .put("session_id", key.sessionId())
                .put("mode", key.mode())
                .put("kind", key.kind())
                .put("batched", true)
                .put("materials", batch.materials())
                .put("total_amount", batch.total)
                .put("event_count", batch.events)
                .put("window_start_ms", batch.startedAt)
                .put("window_end_ms", batch.lastAt)
                .put("container_type", key.containerType())
                .put("owner_uuid", key.ownerId());
        if (batch.delivered + batch.undelivered > 0) {
            row.put("delivered_amount", batch.delivered).put("undelivered_amount", batch.undelivered);
        }
        putBounds(row, batch);
        emitter.emit(row.build());
    }

    private static void putBounds(AuditEmitter.Builder row, ItemBatcher.Batch batch) {
        if (batch.world == null) return;
        row.put("world", batch.world).put("x", batch.firstX).put("y", batch.firstY).put("z", batch.firstZ)
                .put("min_x", batch.minX).put("min_y", batch.minY).put("min_z", batch.minZ)
                .put("max_x", batch.maxX).put("max_y", batch.maxY).put("max_z", batch.maxZ);
    }

    /**
     * Gamemode change of a staff member or a session player; {@code session} may be null.
     * Actor: nobody for DoubleLife's own changes, the command issuer (matched in the same tick,
     * empty for console and command blocks) for COMMAND and for PLUGIN changes made while a
     * command is being dispatched (plugin commands such as EssentialsX /gm), the player otherwise.
     */
    public void gamemode(Player player, SessionData session, PlayerGameModeChangeEvent event) {
        emitter.guarded(() -> {
            GameMode from = player.getGameMode();
            String context = systemContext.get(player.getUniqueId());
            UUID sessionId = session == null ? null : session.getSessionId();
            PlayerGameModeChangeEvent.Cause cause = event.getCause();
            boolean vanillaCommand = context == null && cause == PlayerGameModeChangeEvent.Cause.COMMAND;
            CommandSource pluginSource = context == null && cause == PlayerGameModeChangeEvent.Cause.PLUGIN
                    ? currentCommand() : null;
            boolean byCommand = vanillaCommand || pluginSource != null;
            CommandSource source = vanillaCommand ? currentCommand() : pluginSource;
            UUID actor = context != null ? null : byCommand ? (source == null ? null : source.sourceId()) : player.getUniqueId();
            emitter.emit(AuditEmitter.row(GAMEMODE)
                    .risk(event.getNewGameMode() == GameMode.CREATIVE ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .subject(player.getUniqueId())
                    .actor(actor)
                    .correlation(sessionId)
                    .business(sessionId == null ? player.getUniqueId().toString() : sessionId.toString())
                    .put("session_id", sessionId)
                    .put("in_session", session != null)
                    .put("from", from.name())
                    .put("to", event.getNewGameMode().name())
                    .put("cause", event.getCause().name())
                    .put("actor_type", context == null ? event.getCause().name().toLowerCase() : context)
                    .put("source_dispatcher", byCommand ? (source == null ? "unknown" : source.dispatcher()) : null)
                    .put("source_command_name", source == null ? null : CommandNames.label(source.message()))
                    .put("source_session_id", source == null ? null : source.sessionId())
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    public void teleport(Player player, SessionData session, PlayerTeleportEvent event) {
        emitter.guarded(() -> {
            emitter.emit(SessionAuditor.sessionRow(TELEPORT, player, session)
                    .put("cause", event.getCause().name())
                    .putAll(AuditValues.location(event.getFrom(), "from_"))
                    .putAll(AuditValues.location(event.getTo()))
                    .build());
        });
    }

    public void containerOpened(Player player, SessionData session, Inventory inventory) {
        emitter.guarded(() -> {
            InventoryHolder holder = inventory.getHolder(false);
            Location containerLocation = inventory.getLocation();
            Location location = containerLocation != null ? containerLocation : player.getLocation();
            emitter.emit(SessionAuditor.sessionRow(CONTAINER_OPENED, player, session)
                    .put("inventory_type", inventory.getType().name())
                    .put("holder_type", holder == null ? "none" : holder.getClass().getSimpleName())
                    .put("size", inventory.getSize())
                    .putAll(AuditValues.location(location))
                    .build());
        });
    }

    /**
     * The player whose inventory or ender chest {@code holder} is, when that is not the viewer (invsee and
     * the like). Read from the holder of the open inventory; null otherwise.
     */
    public static UUID otherOwner(Player viewer, InventoryHolder holder) {
        if (holder instanceof HumanEntity human && !human.getUniqueId().equals(viewer.getUniqueId())) {
            return human.getUniqueId();
        }
        return null;
    }

    /**
     * One item movement. {@code delivered} is only set for drops (true when the Item entity is
     * valid and alive one tick later).
     */
    public record ItemMove(String eventType, Player player, SessionData session, String kind, String material,
                           int amount, Location location, String containerType, UUID ownerId,
                           Boolean delivered) {

        /** The material name is read now, so the stack is not copied; null for an empty stack. */
        public static ItemMove out(Player player, SessionData session, String kind, ItemStack item, int amount,
                                   Location location) {
            String material = AuditValues.isEmpty(item) ? null : item.getType().name();
            return new ItemMove(ITEM_OUT, player, session, kind, material, amount, location, null, null, null);
        }

        public ItemMove withContainer(String type, UUID owner) {
            return new ItemMove(eventType, player, session, kind, material, amount, location, type, owner, delivered);
        }

        public ItemMove asIn() {
            return new ItemMove(ITEM_IN, player, session, kind, material, amount, location, containerType, ownerId, delivered);
        }

        public ItemMove withDelivered(boolean value) {
            return new ItemMove(eventType, player, session, kind, material, amount, location, containerType, ownerId, value);
        }
    }
}
