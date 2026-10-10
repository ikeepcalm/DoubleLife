package dev.ua.ikeepcalm.doublelife.audit;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts Bukkit objects into plain audit metadata values. Main thread only: every
 * method reads live Bukkit state, and the returned maps contain only plain values.
 */
public final class AuditValues {

    private AuditValues() {
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public static Map<String, Object> location(Location location, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (location == null || location.getWorld() == null) {
            return values;
        }
        values.put(prefix + "world", location.getWorld().getName());
        values.put(prefix + "x", location.getBlockX());
        values.put(prefix + "y", location.getBlockY());
        values.put(prefix + "z", location.getBlockZ());
        return values;
    }

    public static Map<String, Object> location(Location location) {
        return location(location, "");
    }

    /** Stack and item counts; no item list, no NBT hash and no item ids, which would read every stack's data on the main thread. */
    public static Map<String, Object> summary(ItemStack[] items, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        int stacks = 0;
        int count = 0;
        for (ItemStack item : items == null ? new ItemStack[0] : items) {
            if (isEmpty(item)) continue;
            stacks++;
            count += item.getAmount();
        }
        values.put(prefix + "stack_count", stacks);
        values.put(prefix + "item_count", count);
        return values;
    }
}
