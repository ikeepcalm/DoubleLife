package dev.ua.ikeepcalm.doublelife.audit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Best-effort bridge to the optional shared Mysterria audit ledger.
 * <p>
 * Every call is guarded: if the shaded client failed to initialise, or an emit throws,
 * DoubleLife keeps working without audit rows. Callers pass plain values only
 * (strings, numbers, booleans, UUIDs) so a row can safely be emitted from any thread.
 */
public final class AuditEmitter implements AutoCloseable {

    public static final String PRODUCER_ID = "doublelife";

    private static final int MAX_KEYS = 60;
    private static final int MAX_TEXT = 256;
    private static final int MAX_LONG_TEXT = 1_024;
    /** Keys that may use the client's full per-value budget; also any key ending in these suffixes. */
    private static final Set<String> LONG_TEXT_KEYS = Set.of(
            "command", "entry_commands", "granted_nodes",
            "temporary_permissions", "materials", "reapplied_nodes",
            "old_temporary_permissions", "old_entry_commands");
    private static final Set<String> LONG_TEXT_SUFFIXES = Set.of("items");

    /** Null when the audit client failed to initialise; every call is then a no-op. */
    private final AuditProducer producer;

    public AuditEmitter(JavaPlugin plugin) {
        this.producer = createProducer(plugin);
    }

    private static AuditProducer createProducer(JavaPlugin plugin) {
        try {
            return AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                            .resolve("mysterria-audit-spool"),
                    PRODUCER_ID, plugin.getPluginMeta().getVersion());
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("Audit client unavailable; DoubleLife audit events are disabled: " + failure);
            return null;
        }
    }

    /** Emits one audit row; never throws. */
    public void emit(AuditRow row) {
        if (producer == null || row == null || row.eventType() == null || row.eventType().isBlank()) {
            return;
        }
        try {
            producer.emit(row.eventType(), row.outcome(), row.risk(), row.privacy(),
                    row.correlationId() == null ? UUID.randomUUID() : row.correlationId(),
                    row.businessId(), row.actorId(), row.subjectId(), row.targetId(),
                    row.reason() == null ? null : bounded(row.reason(), MAX_TEXT),
                    boundedMetadata(row.metadata()));
        } catch (RuntimeException | LinkageError failure) {
            // Audit delivery is best effort and must never gate gameplay or persistence.
            recordFailure();
        }
    }

    /**
     * Runs audit work, including building its rows, and never throws. Does nothing when the client is unavailable.
     */
    public void guarded(Runnable work) {
        if (producer == null) {
            return;
        }
        try {
            work.run();
        } catch (RuntimeException | LinkageError failure) {
            recordFailure();
        }
    }

    /** Like {@link #guarded(Runnable)} for work that returns a value; {@code fallback} is returned on failure. */
    public <T> T guarded(Supplier<T> work, T fallback) {
        if (producer == null) {
            return fallback;
        }
        try {
            return work.get();
        } catch (RuntimeException | LinkageError failure) {
            recordFailure();
            return fallback;
        }
    }

    private void recordFailure() {
        try {
            producer.recordFailure();
        } catch (RuntimeException | LinkageError ignored) {
            // Failure accounting is itself best effort.
        }
    }

    private static Map<String, Object> boundedMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null || result.size() >= MAX_KEYS) {
                continue;
            }
            result.put(bounded(key, 64), boundedValue(key, value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object boundedValue(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        int limit = isLongText(key) ? MAX_LONG_TEXT : MAX_TEXT;
        return bounded(value instanceof String text ? text : String.valueOf(value), limit);
    }

    private static boolean isLongText(String key) {
        if (LONG_TEXT_KEYS.contains(key)) return true;
        for (String suffix : LONG_TEXT_SUFFIXES) {
            if (key.endsWith(suffix)) return true;
        }
        return false;
    }

    static String bounded(String value, int limit) {
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    @Override
    public void close() {
        if (producer == null) {
            return;
        }
        try {
            producer.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutdown must continue even if the audit client cannot flush.
        }
    }

    public static Builder row(String eventType) {
        return new Builder(eventType);
    }

    public record AuditRow(String eventType, AuditOutcome outcome, AuditRisk risk, AuditPrivacy privacy,
                           UUID correlationId, String businessId, UUID actorId, UUID subjectId,
                           UUID targetId, String reason, Map<String, Object> metadata) {
    }

    public static final class Builder {
        private final String eventType;
        private AuditOutcome outcome = AuditOutcome.OBSERVED;
        private AuditRisk risk = AuditRisk.NORMAL;
        private AuditPrivacy privacy = AuditPrivacy.STAFF_RESTRICTED;
        private UUID correlationId;
        private String businessId;
        private UUID actorId;
        private UUID subjectId;
        private UUID targetId;
        private String reason;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        public Builder outcome(AuditOutcome value) { this.outcome = value; return this; }
        public Builder risk(AuditRisk value) { this.risk = value; return this; }
        public Builder privacy(AuditPrivacy value) { this.privacy = value; return this; }
        public Builder correlation(UUID value) { this.correlationId = value; return this; }
        public Builder business(String value) { this.businessId = value; return this; }
        public Builder actor(UUID value) { this.actorId = value; return this; }
        public Builder subject(UUID value) { this.subjectId = value; return this; }
        public Builder target(UUID value) { this.targetId = value; return this; }
        public Builder reason(String value) { this.reason = value; return this; }

        public Builder put(String key, Object value) {
            if (key != null && value != null) {
                metadata.put(key, value instanceof UUID uuid ? uuid.toString() : value);
            }
            return this;
        }

        public Builder putAll(Map<String, ?> values) {
            if (values != null) {
                values.forEach(this::put);
            }
            return this;
        }

        public AuditRow build() {
            return new AuditRow(eventType, outcome, risk, privacy, correlationId, businessId,
                    actorId, subjectId, targetId, reason, new LinkedHashMap<>(metadata));
        }
    }
}
