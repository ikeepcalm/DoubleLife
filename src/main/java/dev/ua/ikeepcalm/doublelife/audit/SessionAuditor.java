package dev.ua.ikeepcalm.doublelife.audit;

import dev.ua.ikeepcalm.doublelife.config.PluginConfig;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emits the session lifecycle, permission and op rows. All methods run on the main thread
 * except {@link #permissionChanged} (a LuckPerms thread) and {@link #riskScored} (the async
 * reporting thread), which therefore take plain values only.
 */
public final class SessionAuditor {

    public static final String ENTERED = "doublelife.staff.admin_mode.entered";
    public static final String EXITED = "doublelife.staff.admin_mode.exited";
    public static final String RISK_SCORED = "doublelife.staff.admin_mode.risk_scored";
    public static final String RESUMED = "doublelife.staff.admin_mode.resumed";
    public static final String QUIT = "doublelife.staff.admin_mode.quit_during_session";
    public static final String EXTENDED = "doublelife.staff.admin_mode.extended";
    public static final String ACTION = "doublelife.staff.admin_mode.action";
    public static final String PERMISSION_GRANTED = "doublelife.staff.permission.granted";
    public static final String PERMISSION_REVOKED = "doublelife.staff.permission.revoked";
    public static final String OP_REVOKED = "doublelife.staff.op.revoked";
    public static final String OP_BLOCKED = "doublelife.staff.op.blocked";
    public static final String RESTRICTED_BLOCKED = "doublelife.staff.command.restricted_blocked";
    public static final String CONFIG_RELOADED = "doublelife.system.config.reloaded";

    private final AuditEmitter emitter;

    public SessionAuditor(AuditEmitter emitter) {
        this.emitter = emitter;
    }

    static AuditEmitter.Builder sessionRow(String event, Player player, SessionData session) {
        return AuditEmitter.row(event)
                .actor(player.getUniqueId())
                .correlation(session.getSessionId())
                .business(session.getSessionId().toString())
                .put("session_id", session.getSessionId())
                .put("mode", session.getMode().name());
    }

    /** Emitted once the snapshot is taken, the inventory cleared and admin mode applied. */
    public void entered(Player player, SessionData session, String trigger, long maxMinutes,
                        List<String> entryCommands, AdminModeResult grant) {
        emitter.guarded(() -> {
            ItemStack[] snapshot = snapshotContents(session);
            emitter.emit(sessionRow(ENTERED, player, session)
                    .outcome(AuditOutcome.COMMITTED)
                    .risk(session.getMode() == DoubleLifeMode.TURBO ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .put("trigger", trigger)
                    .put("max_minutes", maxMinutes)
                    .put("entry_commands", String.join(" | ", entryCommands))
                    .put("granted_nodes", grant == null ? "" : String.join(",", grant.nodes()))
                    .put("lp_user_loaded", grant == null ? null : grant.lpUserLoaded())
                    .put("gamemode", player.getGameMode().name())
                    .putAll(AuditValues.location(player.getLocation()))
                    .putAll(AuditValues.summary(snapshot, "snapshot_"))
                    .build());
        });
    }

    /**
     * Emitted after the snapshot was restored and admin mode removed.
     * {@code before} carries the location and gamemode captured just before the restore.
     */
    public void exited(Player player, SessionData session, ExitDetails details) {
        emitter.guarded(() -> {
            emitter.emit(sessionRow(EXITED, player, session)
                    .outcome(details.restoreOk() ? AuditOutcome.COMMITTED : AuditOutcome.FAILED)
                    .reason(details.restoreOk() ? null : "restore_failed")
                    .put("reason", details.reason())
                    .put("player_online", player.isOnline())
                    .put("duration_s", session.getDuration().getSeconds())
                    .put("extension_min", session.getExtensionMinutes())
                    .put("activity_count", session.getActivities().size())
                    .put("restore_ok", details.restoreOk())
                    .put("lp_user_loaded", details.removal() == null ? null : details.removal().lpUserLoaded())
                    .putAll(details.before())
                    .build());
        });
    }

    /**
     * Emitted instead of {@link #exited} when ending the session threw. The timer, boss bar and TURBO
     * nodes are removed anyway ({@code removal}); the snapshot stays pending for a retry on the next join.
     */
    public void exitFailed(Player player, SessionData session, String reason, RuntimeException failure,
                           AdminModeRemoval removal) {
        emitter.guarded(() -> {
            emitter.emit(sessionRow(EXITED, player, session)
                    .outcome(AuditOutcome.FAILED)
                    .risk(session.getMode() == DoubleLifeMode.TURBO ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .reason("end_failed")
                    .put("reason", reason)
                    .put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage())
                    .put("player_online", player.isOnline())
                    .put("duration_s", session.getDuration().getSeconds())
                    .put("extension_min", session.getExtensionMinutes())
                    .put("activity_count", session.getActivities().size())
                    .put("lp_user_loaded", removal == null ? null : removal.lpUserLoaded())
                    .build());
        });
    }

    /**
     * Emitted when the async end-of-session scoring completes, so the exited row never waits for it.
     * Plain values only: it runs off the main thread.
     */
    public void riskScored(SessionData session, RiskAssessment risk) {
        emitter.guarded(() -> {
            if (risk == null) {
                return;
            }
            emitter.emit(AuditEmitter.row(RISK_SCORED)
                    .actor(session.getPlayerId())
                    .correlation(session.getSessionId())
                    .business(session.getSessionId().toString())
                    .risk(risk.getLevel() == RiskLevel.HIGH ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .put("session_id", session.getSessionId())
                    .put("mode", session.getMode().name())
                    .put("risk_level", risk.getLevel().name())
                    .put("risk_score", risk.getScore())
                    .build());
        });
    }

    public Map<String, Object> captureBeforeRestore(Player player) {
        return emitter.guarded(() -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.putAll(AuditValues.location(player.getLocation()));
            values.put("gamemode_before", player.getGameMode().name());
            return values;
        }, Map.of());
    }

    /** {@code outcome} is resumed, expired, skipped_active or failed. */
    public void resumed(Player player, SessionData session, String outcome, List<String> reappliedNodes, long elapsedMinutes) {
        emitter.guarded(() -> {
            AuditOutcome auditOutcome = switch (outcome) {
                case "resumed" -> AuditOutcome.COMMITTED;
                case "failed" -> AuditOutcome.FAILED;
                default -> AuditOutcome.CANCELLED;
            };
            emitter.emit(sessionRow(RESUMED, player, session)
                    .outcome(auditOutcome)
                    .reason(auditOutcome == AuditOutcome.COMMITTED ? null : outcome)
                    .put("outcome", outcome)
                    .put("reapplied_nodes", String.join(",", reappliedNodes))
                    .put("elapsed_min", elapsedMinutes)
                    .put("inventory_cleared", false)
                    .put("restored_activity_count", session.getActivities().size())
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    /** Emitted from PlayerQuitEvent before the session is ended and the snapshot restored. */
    public void quitDuringSession(Player player, SessionData session, long minutesRemaining) {
        emitter.guarded(() -> {
            emitter.emit(sessionRow(QUIT, player, session)
                    .risk(AuditRisk.HIGH)
                    .put("gamemode", player.getGameMode().name())
                    .put("minutes_remaining", minutesRemaining)
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    public void extended(Player player, SessionData session, int addedMinutes, long newTotalMinutes, boolean applied) {
        emitter.guarded(() -> {
            emitter.emit(sessionRow(EXTENDED, player, session)
                    .outcome(applied ? AuditOutcome.COMMITTED : AuditOutcome.DENIED)
                    .reason(applied ? null : "cap_exceeded")
                    .put("added_min", addedMinutes)
                    .put("new_total_min", newTotalMinutes)
                    .put("lp_expiry_updated", false)
                    .build());
        });
    }

    public void entryCommand(Player player, SessionData session, String command, boolean dispatched) {
        emitter.guarded(() -> {
            emitter.emit(sessionRow(ACTION, player, session)
                    .outcome(dispatched ? AuditOutcome.OBSERVED : AuditOutcome.FAILED)
                    .risk(AuditRisk.HIGH)
                    .put("kind", "command")
                    .put("dispatcher", "console")
                    .put("command", command)
                    .put("dispatch_result", dispatched)
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    /**
     * One LuckPerms node grant or revoke. Called after the LuckPerms save completed (or failed),
     * possibly off the main thread: every argument is a plain value.
     */
    public void permissionChanged(PermissionChange change) {
        emitter.guarded(() -> {
            boolean grant = change.grant();
            AuditOutcome outcome = switch (change.saveState()) {
                case "saved" -> AuditOutcome.COMMITTED;
                case "pending" -> AuditOutcome.ATTEMPTED;
                default -> AuditOutcome.FAILED;
            };
            emitter.emit(AuditEmitter.row(grant ? PERMISSION_GRANTED : PERMISSION_REVOKED)
                    .outcome(outcome)
                    .risk(AuditRisk.HIGH)
                    .subject(change.subjectId())
                    .correlation(change.sessionId())
                    .business(change.sessionId() == null ? null : change.sessionId().toString())
                    .reason(outcome == AuditOutcome.COMMITTED ? null : change.saveState())
                    .put("session_id", change.sessionId())
                    .put("actor_type", "system")
                    .put("action", grant ? "grant" : "revoke")
                    .put("node", change.node())
                    .put("value", change.value())
                    .put("expiry", change.expiry())
                    .put("lp_result", change.lpResult())
                    .build());
        });
    }

    /** {@code reason} is whitelist or session_end. {@code wasOp} is null when the caller did not read it (the row is then ATTEMPTED). */
    public void opRevoked(Player player, String reason, Boolean wasOp, UUID sessionId) {
        emitter.guarded(() -> {
            emitter.emit(AuditEmitter.row(OP_REVOKED)
                    .outcome(wasOp == null ? AuditOutcome.ATTEMPTED : wasOp ? AuditOutcome.COMMITTED : AuditOutcome.OBSERVED)
                    .risk(Boolean.TRUE.equals(wasOp) ? AuditRisk.HIGH : AuditRisk.NORMAL)
                    .subject(player.getUniqueId())
                    .correlation(sessionId)
                    .business(sessionId == null ? player.getUniqueId().toString() : sessionId.toString())
                    .put("session_id", sessionId)
                    .put("reason", reason)
                    .put("was_op", wasOp)
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    public void opBlocked(Player issuer, String targetName, String rawCommand) {
        emitter.guarded(() -> {
            emitter.emit(AuditEmitter.row(OP_BLOCKED)
                    .outcome(AuditOutcome.DENIED)
                    .risk(AuditRisk.HIGH)
                    .actor(issuer.getUniqueId())
                    .reason("not_whitelisted")
                    .put("target_name", targetName)
                    .put("command", rawCommand)
                    .putAll(AuditValues.location(issuer.getLocation()))
                    .build());
        });
    }

    public void restrictedCommandBlocked(Player player, CommandNames.Parsed command, String rawCommand, String group) {
        emitter.guarded(() -> {
            emitter.emit(AuditEmitter.row(RESTRICTED_BLOCKED)
                    .outcome(AuditOutcome.DENIED)
                    .actor(player.getUniqueId())
                    .reason("session_required")
                    .put("command_name", command.label())
                    .put("command", rawCommand)
                    .put("group", group)
                    .putAll(AuditValues.location(player.getLocation()))
                    .build());
        });
    }

    /** {@code before} is the config object that was live before the reload, so its values are already in memory. */
    public void configReloaded(CommandSender sender, PluginConfig before, PluginConfig config) {
        emitter.guarded(() -> {
            emitter.emit(reloadRow(sender, before)
                    .outcome(AuditOutcome.COMMITTED)
                    .put("op_whitelist_enabled", config.isOpWhitelistEnabled())
                    .put("temporary_permissions", String.join(",", config.getTemporaryPermissions()))
                    .put("entry_commands", String.join(" | ", config.getEntryCommands()))
                    .put("max_minutes", config.getMaxDuration())
                    .build());
        });
    }

    /** The reload threw; the row carries the values that were live before it. */
    public void configReloadFailed(CommandSender sender, PluginConfig before, RuntimeException failure) {
        emitter.guarded(() -> {
            emitter.emit(reloadRow(sender, before)
                    .outcome(AuditOutcome.FAILED)
                    .reason(failure.getClass().getSimpleName())
                    .put("error", failure.getMessage())
                    .build());
        });
    }

    private static AuditEmitter.Builder reloadRow(CommandSender sender, PluginConfig before) {
        boolean console = !(sender instanceof Player);
        AuditEmitter.Builder row = AuditEmitter.row(CONFIG_RELOADED)
                .actor(sender instanceof Player player ? player.getUniqueId() : null)
                .put("actor_type", console ? "console" : "player")
                .put("actor_name", sender.getName());
        if (before != null) {
            row.put("old_op_whitelist_enabled", before.isOpWhitelistEnabled())
                    .put("old_temporary_permissions", String.join(",", before.getTemporaryPermissions()))
                    .put("old_entry_commands", String.join(" | ", before.getEntryCommands()))
                    .put("old_max_minutes", before.getMaxDuration());
        }
        return row;
    }

    private static ItemStack[] snapshotContents(SessionData session) {
        PlayerState state = session.getSavedState();
        List<ItemStack> items = new ArrayList<>();
        if (state != null) {
            addNonNull(items, state.getInventory());
            addNonNull(items, state.getArmor());
            addNonNull(items, new ItemStack[]{state.getOffHand()});
        }
        return items.toArray(new ItemStack[0]);
    }

    private static void addNonNull(List<ItemStack> into, ItemStack[] items) {
        if (items == null) return;
        for (ItemStack item : items) {
            if (item != null) into.add(item);
        }
    }

    public record AdminModeResult(boolean lpUserLoaded, List<String> nodes) {
    }

    public record ExitDetails(String reason, boolean restoreOk, AdminModeRemoval removal,
                              Map<String, Object> before) {
    }

    /** {@code lpUserLoaded} is null when the removal threw first. */
    public record AdminModeRemoval(Boolean lpUserLoaded) {
    }

    /** {@code saveState} is saved, pending (save still running at shutdown), failed or lp_user_not_loaded. */
    public record PermissionChange(UUID subjectId, UUID sessionId, boolean grant, String node, boolean value,
                                   String expiry, String lpResult, String saveState) {
    }
}
