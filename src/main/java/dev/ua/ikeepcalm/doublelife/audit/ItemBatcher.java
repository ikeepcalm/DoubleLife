package dev.ua.ikeepcalm.doublelife.audit;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Folds high-frequency, untracked item and block activity into one row per
 * session/kind/container per window, so a staff member reorganising a chest or
 * building does not produce a row per click or per block. Events add from the main
 * thread; the stale flush runs on an async timer, so every entry point is synchronized.
 */
final class ItemBatcher {

    static final long WINDOW_MILLIS = 10_000L;
    private static final int MAX_MATERIALS = 48;

    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private final Consumer<Batch> sink;

    ItemBatcher(Consumer<Batch> sink) {
        this.sink = sink;
    }

    synchronized void add(BatchKey key, String material, int amount, Location location, Boolean delivered) {
        long now = System.currentTimeMillis();
        String id = key.id();
        Batch batch = batches.get(id);
        if (batch != null && now - batch.startedAt >= WINDOW_MILLIS) {
            sink.accept(batches.remove(id));
            batch = null;
        }
        if (batch == null) {
            batch = new Batch(key, now);
            batches.put(id, batch);
        }
        batch.add(material, amount, location, delivered, now);
    }

    synchronized void flushStale() {
        long now = System.currentTimeMillis();
        flushWhere(batch -> now - batch.startedAt >= WINDOW_MILLIS);
    }

    synchronized void flushSession(UUID sessionId) {
        flushWhere(batch -> batch.key.sessionId().equals(sessionId));
    }

    synchronized void flushAll() {
        flushWhere(batch -> true);
    }

    private void flushWhere(java.util.function.Predicate<Batch> condition) {
        List<Batch> ready = new ArrayList<>();
        Iterator<Batch> iterator = batches.values().iterator();
        while (iterator.hasNext()) {
            Batch batch = iterator.next();
            if (condition.test(batch)) {
                ready.add(batch);
                iterator.remove();
            }
        }
        ready.forEach(sink);
    }

    record BatchKey(String eventType, UUID sessionId, UUID actorId, String mode, String kind,
                    String containerType, String containerKey, UUID ownerId) {
        String id() {
            return eventType + '|' + sessionId + '|' + kind + '|' + containerType + '|' + containerKey;
        }
    }

    static final class Batch {
        final BatchKey key;
        final long startedAt;
        long lastAt;
        final Map<String, Integer> counts = new TreeMap<>();
        int total;
        int events;
        int delivered;
        int undelivered;
        String world;
        int firstX, firstY, firstZ;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        Batch(BatchKey key, long startedAt) {
            this.key = key;
            this.startedAt = startedAt;
        }

        void add(String material, int amount, Location location, Boolean wasDelivered, long now) {
            merge(counts, material, "OTHER", MAX_MATERIALS, amount);
            total += amount;
            events++;
            lastAt = now;
            if (wasDelivered != null) {
                if (wasDelivered) delivered += amount; else undelivered += amount;
            }
            track(location);
        }

        private void track(Location location) {
            if (location == null || location.getWorld() == null) return;
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            if (world == null) {
                world = location.getWorld().getName();
                firstX = x; firstY = y; firstZ = z;
            }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }

        private static void merge(Map<String, Integer> target, String key, String overflow, int max, int amount) {
            target.merge(target.containsKey(key) || target.size() < max ? key : overflow, amount, Integer::sum);
        }

        String materials() {
            return list(counts);
        }

        private static String list(Map<String, Integer> values) {
            StringBuilder text = new StringBuilder();
            values.forEach((label, count) -> {
                if (!text.isEmpty()) text.append(", ");
                text.append(label).append(" x").append(count);
            });
            return text.toString();
        }
    }
}
