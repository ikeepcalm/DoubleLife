package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.AdminModeRemoval;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.AdminModeResult;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.ExitDetails;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.PermissionChange;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, SessionData> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    // Loaded sessions to resume, and ended sessions whose snapshot is not restored yet
    private List<SessionData> pendingSessions = new ArrayList<>();
    // Loaded session files; tracked until the session is restored and its file removed
    private final Map<SessionData, SessionFile> sessionFiles = new IdentityHashMap<>();
    // Snapshot applied but the playerdata save failed; only the save is retried
    private final Map<SessionData, Player> unsavedRestores = new IdentityHashMap<>();
    // Files that failed to load; never overwritten
    private final Set<File> unreadableFiles = new HashSet<>();
    /** Set while the plugin is disabling: LuckPerms saves are awaited and no async tasks are scheduled. */
    private volatile boolean shuttingDown = false;

    private static final String SESSIONS_FOLDER = "sessions";
    // Written over a restored session's file that could not be deleted
    private static final String RESTORED_KEY = "restoredSession";

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
        loadPendingSessionsFromFile();
    }

    private SessionAuditor audit() {
        return plugin.getSessionAuditor();
    }

    public boolean canStartSession(Player player) {
        return canStartSession(player, DoubleLifeMode.DEFAULT);
    }

    public boolean canStartSession(Player player, DoubleLifeMode mode) {
        retryRestoredFileCleanup();
        // A new session would snapshot the previous session's state while that one is still unrestored
        if (hasActiveSession(player) || hasUnsettledSession(player.getUniqueId())) {
            return false;
        }

        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return false;
        }

        // Check basic permission first
        if (!player.hasPermission("doublelife.use")) {
            return false;
        }

        // Check turbo-specific permission for turbo mode
        if (mode == DoubleLifeMode.TURBO && !player.hasPermission("doublelife.turbo")) {
            return false;
        }

        return true;
    }

    public boolean hasTurboPermission(Player player) {
        return player.hasPermission("doublelife.turbo");
    }

    public void startSession(Player player, DoubleLifeMode mode) {
        startSession(player, mode, "command");
    }

    /** {@code trigger} (command or gui) is recorded on the entered audit row. */
    public void startSession(Player player, DoubleLifeMode mode, String trigger) {
        if (!canStartSession(player, mode)) {
            return;
        }

        PlayerState savedState = PlayerState.capture(player);
        SessionData session = new SessionData(player.getUniqueId(), savedState, mode);
        activeSessions.put(player.getUniqueId(), session);

        // Clear inventory and execute entry commands for both modes
        player.getInventory().clear();
        executeEntryCommands(player, session);

        AdminModeResult grant = null;
        if (mode == DoubleLifeMode.TURBO) {
            grant = applyAdminMode(player, session);
            // Send immediate Discord notification for turbo mode activation
            plugin.getWebhookUtil().sendTurboModeActivation(player.getName());
        }

        startTimer(player, session);
        createBossBar(player, mode);

        String modeMessage = mode == DoubleLifeMode.TURBO ? "session.turbo-start-success" : "session.default-start-success";
        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage(modeMessage, player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-started", player.getName(), mode.getDisplayName()));
        audit().entered(player, session, trigger, plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getEntryCommands(), grant);
    }

    public void startSession(Player player) {
        startSession(player, DoubleLifeMode.DEFAULT);
    }

    public void endSession(Player player) {
        endSession(player, "manual");
    }

    /** {@code reason} is manual, gui, expired, quit, shutdown or restore_retry. */
    public void endSession(Player player, String reason) {
        SessionData session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            endOrKeepPending(player, session, reason);
        }
    }

    // If ending throws, still remove the timer, boss bar and TURBO nodes so a pending session keeps no privileges
    private void endOrKeepPending(Player player, SessionData session, String reason) {
        try {
            finishSession(player, session, reason);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to end session for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            stopTimerAndBossBar(player);
            AdminModeRemoval removal = null;
            if (session.getMode() == DoubleLifeMode.TURBO) {
                removal = removeAdminModeSafely(player, session);
            }
            audit().exitFailed(player, session, reason, e, removal);
        }
    }

    // The session stays pending, ended so it is never resumed, until its snapshot is restored and saved.
    // A retry only restores again: the end time, report and cooldown are set once.
    private void finishSession(Player player, SessionData session, String reason) {
        boolean firstEnd = session.isActive();
        if (firstEnd) {
            session.end();
        }
        if (!pendingSessions.contains(session)) {
            pendingSessions.add(session);
        }
        plugin.getActivityListener().flushBlocks(player.getUniqueId(), session);
        plugin.getActivityAuditor().flushSession(session);
        // The exit row is written once, so a retry does not read the state for it
        Map<String, Object> before = firstEnd ? audit().captureBeforeRestore(player) : Map.of();
        boolean restoreOk = restorePlayerState(player, session);
        if (restoreOk) {
            settleSession(session);
        }

        AdminModeRemoval removal = null;
        if (session.getMode() == DoubleLifeMode.TURBO) {
            removal = removeAdminModeSafely(player, session);
        }

        stopTimerAndBossBar(player);

        if (firstEnd) {
            // Scoring runs on the reporter's async path; its audit row follows when it completes.
            plugin.getSessionReporter().report(session, player.getName(), risk -> audit().riskScored(session, risk));

            long cooldownDuration = plugin.getPluginConfig().getCooldownDuration() * 1000L;
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownDuration);
        }

        if (restoreOk) {
            player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success", player)));
        } else {
            player.sendMessage(ComponentUtil.warning("Session ended, but your original state could not be restored yet. It will be retried automatically."));
            plugin.getLogger().warning("Session for " + player.getName() + " ended without restoring its snapshot; kept pending for a retry");
        }
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-ended", player.getName()));
        if (firstEnd) {
            audit().exited(player, session, new ExitDetails(reason, restoreOk, removal, before));
        }
    }

    private void stopTimerAndBossBar(Player player) {
        BukkitTask timer = sessionTimers.remove(player.getUniqueId());
        if (timer != null) {
            timer.cancel();
        }

        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void settleSession(SessionData session) {
        pendingSessions.remove(session);
        unsavedRestores.remove(session);
        SessionFile loaded = sessionFiles.get(session);
        if (loaded == null) {
            return;
        }
        if (removeSessionFile(loaded.file())) {
            sessionFiles.remove(session);
        } else {
            sessionFiles.put(session, new SessionFile(loaded.file(), true));
            plugin.getLogger().severe("Failed to delete or mark restored session file " + loaded.file().getName()
                + "; retrying, but if it remains at the next start it restores an old snapshot");
        }
    }

    // If the file cannot be deleted, a marker that loading discards stops it restoring an old snapshot
    private boolean removeSessionFile(File file) {
        if (!file.exists() || file.delete()) {
            return true;
        }
        try {
            Files.writeString(file.toPath(), RESTORED_KEY + ": true\n", StandardCharsets.UTF_8);
            plugin.getLogger().warning("Could not delete restored session file " + file.getName() + "; marked it as restored");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void retryRestoredFileCleanup() {
        sessionFiles.values().removeIf(loaded -> loaded.restored() && removeSessionFile(loaded.file()));
    }

    // Retries only the save, so a snapshot already applied is not applied again over newer items
    private void retryUnsavedRestores(Player player) {
        for (SessionData session : List.copyOf(pendingSessions)) {
            if (!session.getPlayerId().equals(player.getUniqueId())) {
                continue;
            }
            Player applied = unsavedRestores.get(session);
            if (applied == player && savePlayerData(player)) {
                settleSession(session);
            }
        }
    }

    private AdminModeRemoval removeAdminModeSafely(Player player, SessionData session) {
        try {
            return removeAdminMode(player, session);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to remove admin mode for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return new AdminModeRemoval(null);
        }
    }

    public void handleQuit(Player player) {
        retryUnsavedRestores(player);
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        long totalAllowed = session.getTotalAllowedMinutes(plugin.getPluginConfig().getMaxDuration());
        audit().quitDuringSession(player, session, Math.max(0, totalAllowed - session.getDuration().toMinutes()));
        endSession(player, "quit");
    }

    public void shutdown() {
        shuttingDown = true;
        endAllSessions("shutdown");
        for (Player player : List.copyOf(unsavedRestores.values())) {
            if (player.isOnline()) {
                retryUnsavedRestores(player);
            }
        }
        retryRestoredFileCleanup();
        saveSessionsOnShutdown();
        plugin.getActivityAuditor().flushAll();
    }

    public void endAllSessions() {
        endAllSessions("shutdown");
    }

    public void endAllSessions(String reason) {
        Set<UUID> sessionIds = new HashSet<>(activeSessions.keySet());
        for (UUID playerId : sessionIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                endSession(player, reason);
            }
        }
    }

    public void saveSessionsOnShutdown() {
        List<SessionData> toSave = new ArrayList<>(activeSessions.values());
        pendingSessions.stream().filter(session -> !session.isActive()).forEach(toSave::add);
        if (toSave.isEmpty()) {
            return;
        }

        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            sessionsFolder.mkdirs();
        }

        int savedCount = 0;
        for (SessionData session : toSave) {
            try {
                Player player = Bukkit.getPlayer(session.getPlayerId());
                String playerName = player == null ? null : player.getName();
                if (saveSessionToYaml(session, sessionFileFor(session, sessionsFolder, playerName))) {
                    savedCount++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save session for player " + session.getPlayerId() + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Saved " + savedCount + " sessions to individual YAML files");
    }

    // A loaded session keeps its own file; others use the player name, or a unique name if that file is taken
    private File sessionFileFor(SessionData session, File sessionsFolder, String playerName) {
        SessionFile loaded = sessionFiles.get(session);
        if (loaded != null) {
            return loaded.file();
        }
        File byName = new File(sessionsFolder, (playerName != null ? playerName : session.getPlayerId()) + ".yml");
        if (!isFileTaken(byName)) {
            return byName;
        }
        return new File(sessionsFolder, session.getPlayerId() + "-" + UUID.randomUUID() + ".yml");
    }

    private boolean isFileTaken(File file) {
        return unreadableFiles.contains(file)
                || sessionFiles.values().stream().anyMatch(other -> other.file().equals(file));
    }

    // Writes a temporary file first, so a failed write leaves any existing file intact
    private boolean saveSessionToYaml(SessionData session, File sessionFile) {
        try {
            File tempFile = new File(sessionFile.getParentFile(), sessionFile.getName() + ".tmp");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("session", session);
            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), sessionFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            plugin.getLogger().info("Saved session to " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save session YAML " + sessionFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void loadPendingSessionsFromFile() {
        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            return;
        }

        File[] files = sessionsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }

        int loadedCount = 0;
        for (File sessionFile : files) {
            try {
                SessionData session = loadSessionFromYaml(sessionFile);
                if (session != null) {
                    pendingSessions.add(session);
                    sessionFiles.put(session, new SessionFile(sessionFile, false));
                    loadedCount++;
                }
            } catch (Exception e) {
                keepUnreadableFile(sessionFile, e.getMessage());
            }
        }

        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending sessions from YAML files");
        }
    }

    private SessionData loadSessionFromYaml(File sessionFile) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);
            if (yaml.isSet(RESTORED_KEY)) {
                plugin.getLogger().info("Removing session file " + sessionFile.getName() + " whose snapshot was already restored");
                sessionFile.delete();
                return null;
            }

            Object sessionObj = yaml.get("session");
            SessionData session = null;

            if (sessionObj instanceof SessionData) {
                session = (SessionData) sessionObj;
            } else if (sessionObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sessionMap = (Map<String, Object>) sessionObj;
                session = SessionData.deserialize(sessionMap);
            }

            if (session == null) {
                keepUnreadableFile(sessionFile, "no session could be deserialized");
                return null;
            }

            plugin.getLogger().info("Successfully loaded session from " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

            return session;
        } catch (Exception e) {
            e.printStackTrace();
            keepUnreadableFile(sessionFile, e.getMessage());
            return null;
        }
    }

    // The file may hold the only copy of a player's original state, so it is kept for staff
    private void keepUnreadableFile(File sessionFile, String reason) {
        unreadableFiles.add(sessionFile);
        plugin.getLogger().severe("Failed to load session file " + sessionFile.getName() + " (" + reason + "); kept it, "
            + "it may hold a player's original state: fix or restore it manually, loading is retried on the next start");
    }

    public void restoreSessionForPlayer(Player player) {
        retryRestoredFileCleanup();
        if (pendingSessions.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        SessionData sessionToRestore = null;

        for (SessionData session : pendingSessions) {
            if (session.getPlayerId().equals(playerId)) {
                sessionToRestore = session;
                break;
            }
        }

        if (sessionToRestore == null) {
            return;
        }

        long elapsedMinutes = sessionToRestore.getDuration().toMinutes();
        List<String> reapplied = List.of();
        try {
            if (hasActiveSession(player)) {
                plugin.getLogger().warning("Player " + player.getName() + " already has an active session, keeping the saved session pending");
                audit().resumed(player, sessionToRestore, "skipped_active", List.of(), elapsedMinutes);
                return;
            }

            if (!sessionToRestore.isActive()) {
                plugin.getLogger().info("Retrying the restore of an ended session for " + player.getName());
                endOrKeepPending(player, sessionToRestore, "restore_retry");
                return;
            }

            long baseDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = sessionToRestore.getTotalAllowedMinutes(baseDuration);
            if (elapsedMinutes >= totalAllowedMinutes) {
                plugin.getLogger().info("Session for " + player.getName() + " has expired, restoring saved state");
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.expired-during-restart", player)));
                audit().resumed(player, sessionToRestore, "expired", List.of(), elapsedMinutes);
                endOrKeepPending(player, sessionToRestore, "expired");
                return;
            }

            activeSessions.put(playerId, sessionToRestore);

            if (sessionToRestore.getMode() == DoubleLifeMode.TURBO) {
                reapplied = applyAdminMode(player, sessionToRestore).nodes();
            }

            startTimer(player, sessionToRestore);
            createBossBar(player, sessionToRestore.getMode());

            player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.restored-after-restart", player)));

            plugin.getLogger().info("Restored " + sessionToRestore.getMode().getDisplayName() + " session for " + player.getName());
            audit().resumed(player, sessionToRestore, "resumed", reapplied, elapsedMinutes);

            pendingSessions.remove(sessionToRestore);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore session for player " + player.getName() + ": " + e.getMessage());
            audit().resumed(player, sessionToRestore, "failed", List.of(), elapsedMinutes);
            // Partly resumed: ending it undoes what was applied and restores the snapshot
            if (activeSessions.remove(playerId, sessionToRestore)) {
                endOrKeepPending(player, sessionToRestore, "restore_retry");
            }
        }
    }

    // restored: the snapshot is restored and saved, only the file removal is left
    private record SessionFile(File file, boolean restored) {
    }

    // A "-key" entry is a negation of key (value false)
    private record ConfiguredNode(String configured, String key, boolean value) {
        static ConfiguredNode parse(String configured) {
            if (configured.startsWith("-") && configured.length() > 1) {
                return new ConfiguredNode(configured, configured.substring(1), false);
            }
            return new ConfiguredNode(configured, configured, true);
        }
    }

    private AdminModeResult applyAdminMode(Player player, SessionData session) {
        plugin.getLogger().info("Applying admin mode for " + player.getName());
        UUID subjectId = player.getUniqueId();
        UUID sessionId = session.getSessionId();
        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        Duration lifetime = Duration.ofMinutes(plugin.getPluginConfig().getMaxDuration());
        String expiry = Instant.now().plus(lifetime).toString();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            plugin.getLogger().info("User was not found in LuckPerms, withdrawing...");
            permissions.forEach(permission -> emitPermission(subjectId, sessionId, true,
                    ConfiguredNode.parse(permission), expiry, "not_attempted", "lp_user_not_loaded"));
            return new AdminModeResult(false, List.of());
        }

        List<Consumer<String>> rows = new ArrayList<>();
        for (String permission : permissions) {
            ConfiguredNode configured = ConfiguredNode.parse(permission);
            Node node = PermissionNode.builder(configured.key())
                    .value(configured.value())
                    .expiry(lifetime)
                    .build();
            DataMutateResult result = user.data().add(node);
            rows.add(state -> emitPermission(subjectId, sessionId, true, configured, expiry, result.name(), state));
            plugin.getLogger().info("Adding permission " + permission + " to " + player.getName());
        }

        onSaved(plugin.getLuckPerms().getUserManager().saveUser(user), state -> rows.forEach(row -> row.accept(state)));
        plugin.getLogger().info("Saved nodes!");
        return new AdminModeResult(true, List.copyOf(permissions));
    }

    private AdminModeRemoval removeAdminMode(Player player, SessionData session) {
        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        UUID subjectId = player.getUniqueId();
        UUID sessionId = session.getSessionId();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            permissions.forEach(permission -> emitPermission(subjectId, sessionId, false,
                    ConfiguredNode.parse(permission), null, "not_attempted", "lp_user_not_loaded"));
            return new AdminModeRemoval(false);
        }

        NodeMap nodeMap = user.getData(DataType.NORMAL);
        List<Consumer<String>> rows = new ArrayList<>();

        for (String permission : permissions) {
            ConfiguredNode configured = ConfiguredNode.parse(permission);
            clearNodes(user, configured);
            rows.add(state -> emitPermission(subjectId, sessionId, false, configured, null, "cleared", state));

            if (nodeMap.remove(Node.builder(permission).build()) == DataMutateResult.SUCCESS) {
                plugin.getLogger().info(plugin.getLangConfig().getMessage("permissions.removed") + ": " + permission + " for " + player.getName());
            }
        }

        player.setOp(false);
        // Whether the player was op is not read: that would be a lookup only the row uses.
        audit().opRevoked(player, "session_end", null, session.getSessionId());

        onSaved(plugin.getLuckPerms().getUserManager().saveUser(user), state -> rows.forEach(row -> row.accept(state)));
        return new AdminModeRemoval(true);
    }

    // For a negation also clears the temporary key=false node added on entry, never a permanent grant of key
    private static void clearNodes(User user, ConfiguredNode configured) {
        user.data().clear(n -> n.getKey().equals(configured.configured())
                || !configured.value() && n.getKey().equals(configured.key()) && !n.getValue() && n.hasExpiry());
    }

    /** Plain values only: may run on a LuckPerms thread. */
    private void emitPermission(UUID subjectId, UUID sessionId, boolean grant, ConfiguredNode node,
                                String expiry, String lpResult, String saveState) {
        audit().permissionChanged(new PermissionChange(subjectId, sessionId, grant,
                node.key(), node.value(), expiry, lpResult, saveState));
    }

    /**
     * Reports the LuckPerms save outcome as saved, failed or pending. During shutdown the main
     * thread never waits: an already finished save reports its result, otherwise the row says
     * pending because the audit client closes right after. Otherwise the callback runs on the
     * LuckPerms thread with plain captured values only.
     */
    private void onSaved(CompletableFuture<Void> save, Consumer<String> report) {
        if (!shuttingDown) {
            save.whenComplete((ignored, failure) -> report.accept(failure == null ? "saved" : "failed"));
            return;
        }
        if (!save.isDone()) {
            report.accept("pending");
            return;
        }
        report.accept(save.isCompletedExceptionally() ? "failed" : "saved");
    }

    // Returns false instead of throwing so the rest of the cleanup always runs
    private boolean restorePlayerState(Player player, SessionData session) {
        unsavedRestores.remove(session);
        PlayerState state = session.getSavedState();
        if (state == null) {
            plugin.getLogger().warning("Cannot restore player state for " + player.getName() + " - saved state is null!");
            return false;
        }
        plugin.getLogger().info("Restoring player state for " + player.getName());
        try {
            plugin.getActivityAuditor().withSystemContext(player, "session_restore", () -> state.restore(player));
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to restore player state for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        plugin.getLogger().info("Successfully restored player state for " + player.getName());
        if (!savePlayerData(player)) {
            unsavedRestores.put(session, player);
            return false;
        }
        return true;
    }

    // Saved right away so a crash before the next autosave cannot bring back the session inventory
    private boolean savePlayerData(Player player) {
        try {
            player.saveData();
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to save player data for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private void startTimer(Player player, SessionData session) {
        long maxDurationTicks = plugin.getPluginConfig().getMaxDuration() * 60L * 20L;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateBossBar(player, session);

            long maxDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = session.getTotalAllowedMinutes(maxDuration);
            if (session.getDuration().toMinutes() >= totalAllowedMinutes) {
                endSession(player, "expired");
            }
        }, 0L, 20L);

        sessionTimers.put(player.getUniqueId(), task);
    }

    private void createBossBar(Player player, DoubleLifeMode mode) {
        String titleKey = mode == DoubleLifeMode.TURBO ? "bossbar.turbo-active-title" : "bossbar.default-active-title";
        BossBar.Color color = mode == DoubleLifeMode.TURBO ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        
        BossBar bossBar = BossBar.bossBar(
                ComponentUtil.gradient(plugin.getLangConfig().getMessage(titleKey), "#FFD700", "#FF6B35"),
                1.0f,
                color,
                BossBar.Overlay.PROGRESS
        );

        player.showBossBar(bossBar);
        bossBars.put(player.getUniqueId(), bossBar);
    }

    private void updateBossBar(Player player, SessionData session) {
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) return;

        Duration duration = session.getDuration();
        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long totalAllowedMinutes = session.getTotalAllowedMinutes(baseDuration);
        float progress = 1.0f - (float) duration.toMinutes() / totalAllowedMinutes;

        long remainingMinutes = totalAllowedMinutes - duration.toMinutes();
        String titleText = plugin.getLangConfig().getMessage("bossbar.remaining-time", remainingMinutes);
        Component title = ComponentUtil.gradient(titleText, "#FFD700", "#FF6B35");

        bossBar.name(title);
        bossBar.progress(Math.max(0, Math.min(1, progress)));

        if (remainingMinutes <= 1) {
            bossBar.color(BossBar.Color.RED);
        } else if (remainingMinutes <= 5) {
            bossBar.color(BossBar.Color.PINK);
        }
    }

    private void executeEntryCommands(Player player, SessionData session) {
        List<String> commands = plugin.getPluginConfig().getEntryCommands();
        plugin.getActivityAuditor().withSystemContext(player, "entry_command", () -> {
            for (String command : commands) {
                String processedCommand = command.replace("{player}", player.getName());
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                audit().entryCommand(player, session, processedCommand, dispatched);
            }
        });
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    private boolean hasUnsettledSession(UUID playerId) {
        return pendingSessions.stream().anyMatch(session -> session.getPlayerId().equals(playerId))
                || sessionFiles.keySet().stream().anyMatch(session -> session.getPlayerId().equals(playerId));
    }

    public SessionData getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public long getRemainingCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    public boolean prolongSession(Player player, int additionalMinutes) {
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long currentTotalAllowed = session.getTotalAllowedMinutes(baseDuration);
        long newTotalAllowed = currentTotalAllowed + additionalMinutes;

        if (newTotalAllowed > baseDuration * 2) {
            audit().extended(player, session, additionalMinutes, currentTotalAllowed, false);
            return false;
        }

        long extensionMillis = additionalMinutes * 60L * 1000L;
        session.extendSession(extensionMillis);

        updateBossBar(player, session);

        player.sendMessage(ComponentUtil.success(
            plugin.getLangConfig().getMessage("session.extended-success", player, additionalMinutes)
        ));
        plugin.getLogger().info("Extended session for " + player.getName() + " by " + additionalMinutes + " minutes");
        audit().extended(player, session, additionalMinutes, newTotalAllowed, true);

        return true;
    }
}