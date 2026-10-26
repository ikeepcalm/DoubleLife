package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.service.RiskAnalyzer;
import org.bukkit.Bukkit;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-of-session pipeline: write the local log, score the session, ask the AI for a verdict when flagged,
 * then post. Only HIGH sessions that the AI does not clear may ping, and at most once per staff member
 * per ping cooldown, so a run of back-to-back sessions does not flood reviewers.
 */
public class SessionReporter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DoubleLife plugin;
    private final RiskAnalyzer riskAnalyzer;
    private final Map<UUID, Long> lastPing = new ConcurrentHashMap<>();

    public SessionReporter(DoubleLife plugin) {
        this.plugin = plugin;
        this.riskAnalyzer = new RiskAnalyzer(plugin);
    }

    public void report(SessionData session, String playerName) {
        Runnable task = () -> {
            try {
                new LogWriter(plugin, session).writeLog();
                RiskAssessment assessment = riskAnalyzer.analyze(session, playerName);
                if (!assessment.getLevel().isAtLeast(plugin.getPluginConfig().getRiskThreshold())) {
                    plugin.getHistoryService().save(session, playerName, assessment, null, false);
                    plugin.getWebhookUtil().sendCleanSummary(playerName, session, assessment);
                    return;
                }

                String aiVerdict = "AI summary disabled.";
                if (plugin.getPluginConfig().isAiEnabled() && plugin.isEnabled()) {
                    aiVerdict = plugin.getGeminiClient().requestVerdict(playerName, buildActivityLogText(session), assessment);
                }

                plugin.getHistoryService().save(session, playerName, assessment, aiVerdict, true);

                // The AI may only escalate: its input includes text the audited staff member wrote (reason, notes)
                boolean ping = (assessment.getLevel().isAtLeast(plugin.getPluginConfig().getRiskPingThreshold())
                        || aiVerdict.toUpperCase().startsWith("ABUSE"))
                        && claimPing(session.getPlayerId());

                plugin.getLogger().warning("Flagged session for " + playerName + " — Risk: "
                        + assessment.getLevel().getDisplayName() + " (score " + assessment.getScore() + ")" + (ping ? ", pinging" : ""));
                plugin.getWebhookUtil().sendFlaggedAlert(playerName, session, assessment, aiVerdict, ping);
            } catch (Exception e) {
                plugin.getLogger().severe("Error during session reporting for " + playerName + ": " + e.getMessage());
            }
        };
        // During onDisable the scheduler rejects new tasks; run inline so history is still written
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private boolean claimPing(UUID playerId) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.getPluginConfig().getPingCooldownMinutes() * 60_000L;
        Long last = lastPing.get(playerId);
        if (last != null && now - last < cooldown) return false;
        lastPing.put(playerId, now);
        return true;
    }

    private String buildActivityLogText(SessionData session) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mode: ").append(session.getMode().getDisplayName())
                .append(", duration: ").append(session.getDuration().toMinutes()).append(" min\n");
        sb.append("Stated reason: ").append(session.getReason() != null ? session.getReason() : WebhookUtil.NO_REASON).append("\n");

        Map<String, Integer> counts = new TreeMap<>();
        StringBuilder timeline = new StringBuilder();
        int lines = 0;
        for (ActivityLog activity : session.getActivities()) {
            counts.merge(activity.getType().getDisplayName(), 1, Integer::sum);
            boolean important = activity.getType() == ActivityType.COMMAND || activity.getType() == ActivityType.NOTE
                    || activity.getType() == ActivityType.GAMEMODE_CHANGE || activity.getType() == ActivityType.ITEM_DROP;
            if (important && lines++ < 80) {
                timeline.append("[").append(TIME.format(activity.getTimestamp())).append("] ")
                        .append(activity.getType().getDisplayName()).append(": ").append(activity.getDetails()).append("\n");
            }
        }
        sb.append("Activity counts: ").append(counts).append("\n");
        sb.append(timeline);
        return sb.length() > 6000 ? sb.substring(0, 6000) : sb.toString();
    }
}
