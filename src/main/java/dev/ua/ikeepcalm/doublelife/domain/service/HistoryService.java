package dev.ua.ikeepcalm.doublelife.domain.service;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.ActivityLog;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionRecord;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * One JSON file per finished session at history/&lt;uuid&gt;/&lt;startMillis&gt;.json. Summaries (records without
 * activities) are kept in memory for the menus; a full record is only parsed when its timeline is opened.
 */
public class HistoryService {

    private final DoubleLife plugin;
    private final File folder;
    private final Gson gson = new Gson();
    private final Map<UUID, List<SessionRecord>> index = new ConcurrentHashMap<>();

    public HistoryService(DoubleLife plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "history");
        folder.mkdirs();
    }

    public void save(SessionData session, String playerName, RiskAssessment assessment, String aiVerdict, boolean flagged) {
        SessionRecord record = new SessionRecord();
        record.setPlayerId(session.getPlayerId().toString());
        record.setPlayerName(playerName);
        record.setMode(session.getMode());
        record.setStartMillis(System.currentTimeMillis() - session.getDuration().toMillis());
        record.setEndMillis(System.currentTimeMillis());
        record.setReason(session.getReason());
        record.setScore(assessment.getScore());
        record.setRiskLevel(assessment.getLevel());
        record.setFlagged(flagged);
        record.setAiVerdict(aiVerdict);
        record.setFlags(assessment.getFlags());
        List<SessionRecord.Entry> entries = new ArrayList<>();
        for (ActivityLog activity : new ArrayList<>(session.getActivities())) {
            SessionRecord.Entry entry = new SessionRecord.Entry();
            entry.setMillis(activity.getTimestamp().toEpochMilli());
            entry.setType(activity.getType());
            entry.setDetails(activity.getDetails());
            entry.setLocation(activity.getLocation());
            entries.add(entry);
        }
        record.setActivities(entries);
        record.setActivityCount(entries.size());

        File file = fileOf(record);
        file.getParentFile().mkdirs();
        try {
            Files.writeString(file.toPath(), gson.toJson(record), StandardCharsets.UTF_8);
            record.setActivities(null);
            index.computeIfAbsent(session.getPlayerId(), id -> new CopyOnWriteArrayList<>()).add(record);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save session history for " + playerName + ": " + e.getMessage());
        }
    }

    /**
     * Per staff member, newest session first; staff ordered by their latest session.
     */
    public List<List<SessionRecord>> staff() {
        List<List<SessionRecord>> result = new ArrayList<>();
        for (UUID playerId : index.keySet()) {
            List<SessionRecord> records = sessionsOf(playerId);
            if (!records.isEmpty()) result.add(records);
        }
        result.sort(Comparator.comparingLong((List<SessionRecord> r) -> r.getFirst().getEndMillis()).reversed());
        return result;
    }

    public List<SessionRecord> sessionsOf(UUID playerId) {
        List<SessionRecord> records = new ArrayList<>(index.getOrDefault(playerId, List.of()));
        records.sort(Comparator.comparingLong(SessionRecord::getStartMillis).reversed());
        return records;
    }

    /**
     * Blocking: reads the full record (with activities) for a summary from the index.
     */
    public SessionRecord loadFull(SessionRecord summary) {
        return read(fileOf(summary));
    }

    /**
     * Blocking, run once at startup off the main thread: drops expired files and builds the summary index.
     */
    public void loadIndex(int retentionDays) {
        long cutoff = retentionDays > 0 ? System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays) : Long.MIN_VALUE;
        File[] dirs = folder.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) continue;
            for (File file : files) {
                if (file.lastModified() < cutoff) {
                    file.delete();
                    continue;
                }
                SessionRecord record = read(file);
                if (record == null) continue;
                record.setActivities(null);
                index.computeIfAbsent(UUID.fromString(record.getPlayerId()), id -> new CopyOnWriteArrayList<>()).add(record);
            }
        }
    }

    private SessionRecord read(File file) {
        try {
            return gson.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), SessionRecord.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Skipping unreadable history file " + file.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    private File fileOf(SessionRecord record) {
        return new File(new File(folder, record.getPlayerId()), record.getStartMillis() + ".json");
    }
}
