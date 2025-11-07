package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class PlayerState implements ConfigurationSerializable {

    // Transient fields used when working with runtime Player objects
    private transient ItemStack[] inventory;
    private transient ItemStack[] armor;
    private transient ItemStack offHand;
    private transient Collection<PotionEffect> potionEffects;
    private transient Location location;

    // Serializable string representations for persistence
    private String serializedInventory;
    private String serializedArmor;
    private String serializedOffHand;
    private Map<String, Integer> serializedPotionEffects;
    private String worldName;
    private double x, y, z;
    private float yaw, pitch;

    // Simple fields that are easily serializable
    private int level;
    private float experience;
    private int foodLevel;
    private float saturation;
    private double health;
    private String gameMode;
    private boolean allowFlight;
    private boolean isFlying;
    private Map<String, Object> metadata;

    /**
     * Captures the current state of a player to save for later restoration
     */
    public static PlayerState capture(Player player) {
        PlayerStateBuilder builder = PlayerState.builder()
            .level(player.getLevel())
            .experience(player.getExp())
            .foodLevel(player.getFoodLevel())
            .saturation(player.getSaturation())
            .health(player.getHealth())
            .gameMode(player.getGameMode().name())
            .allowFlight(player.getAllowFlight())
            .isFlying(player.isFlying())
            .metadata(new HashMap<>());

        // Store runtime objects
        builder.inventory(player.getInventory().getStorageContents().clone());
        builder.armor(player.getInventory().getArmorContents().clone());
        builder.offHand(player.getInventory().getItemInOffHand().clone());
        builder.potionEffects(player.getActivePotionEffects());
        builder.location(player.getLocation().clone());

        // Also serialize for potential persistence
        Location loc = player.getLocation();
        if (loc != null && loc.getWorld() != null) {
            builder.worldName(loc.getWorld().getName());
            builder.x(loc.getX());
            builder.y(loc.getY());
            builder.z(loc.getZ());
            builder.yaw(loc.getYaw());
            builder.pitch(loc.getPitch());
        }

        builder.serializedInventory(serializeItemStackArray(player.getInventory().getStorageContents()));
        builder.serializedArmor(serializeItemStackArray(player.getInventory().getArmorContents()));
        builder.serializedOffHand(serializeItemStack(player.getInventory().getItemInOffHand()));

        Map<String, Integer> effects = new HashMap<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.put(effect.getType().getName(), effect.getDuration());
        }
        builder.serializedPotionEffects(effects);

        return builder.build();
    }

    /**
     * Restores this saved state to the specified player
     */
    public void restore(Player player) {
        // Use runtime objects if available, otherwise deserialize
        ItemStack[] inventoryToRestore = inventory != null ? inventory : deserializeItemStackArray(serializedInventory);
        ItemStack[] armorToRestore = armor != null ? armor : deserializeItemStackArray(serializedArmor);
        ItemStack offHandToRestore = offHand != null ? offHand : deserializeItemStack(serializedOffHand);
        Collection<PotionEffect> effectsToRestore = potionEffects != null ? potionEffects : deserializePotionEffects();
        Location locationToRestore = location != null ? location : deserializeLocation();

        player.getInventory().clear();
        player.getInventory().setStorageContents(inventoryToRestore);
        player.getInventory().setArmorContents(armorToRestore);
        player.getInventory().setItemInOffHand(offHandToRestore);

        player.setLevel(level);
        player.setExp(experience);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setHealth(health);

        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        effectsToRestore.forEach(player::addPotionEffect);

        if (locationToRestore != null) {
            player.teleport(locationToRestore);
        }
        player.setGameMode(GameMode.valueOf(gameMode));
        player.setAllowFlight(allowFlight);
        player.setFlying(isFlying);
    }

    private static String serializeItemStack(ItemStack item) {
        if (item == null) return null;
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static String serializeItemStackArray(ItemStack[] items) {
        if (items == null) return null;
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(items);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack deserializeItemStack(String data) {
        if (data == null) return null;
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack[] deserializeItemStackArray(String data) {
        if (data == null) return new ItemStack[0];
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack[] items = (ItemStack[]) dataInput.readObject();
            dataInput.close();
            return items;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }

    private Collection<PotionEffect> deserializePotionEffects() {
        Collection<PotionEffect> effects = new java.util.ArrayList<>();
        if (serializedPotionEffects != null) {
            for (Map.Entry<String, Integer> entry : serializedPotionEffects.entrySet()) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(entry.getKey());
                    if (type != null) {
                        effects.add(new PotionEffect(type, entry.getValue(), 1));
                    }
                } catch (Exception ignored) {}
            }
        }
        return effects;
    }

    private Location deserializeLocation() {
        if (worldName == null) return null;
        try {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return new Location(world, x, y, z, yaw, pitch);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();

        map.put("serializedInventory", serializedInventory);
        map.put("serializedArmor", serializedArmor);
        map.put("serializedOffHand", serializedOffHand);
        map.put("level", level);
        map.put("experience", experience);
        map.put("foodLevel", foodLevel);
        map.put("saturation", saturation);
        map.put("health", health);
        map.put("potionEffects", serializedPotionEffects);
        map.put("worldName", worldName);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", yaw);
        map.put("pitch", pitch);
        map.put("gameMode", gameMode);
        map.put("allowFlight", allowFlight);
        map.put("isFlying", isFlying);
        map.put("metadata", metadata);

        return map;
    }

    public static PlayerState deserialize(Map<String, Object> map) {
        PlayerStateBuilder builder = PlayerState.builder();

        builder.serializedInventory((String) map.get("serializedInventory"));
        builder.serializedArmor((String) map.get("serializedArmor"));
        builder.serializedOffHand((String) map.get("serializedOffHand"));
        builder.level((Integer) map.getOrDefault("level", 0));
        builder.experience(((Number) map.getOrDefault("experience", 0.0f)).floatValue());
        builder.foodLevel((Integer) map.getOrDefault("foodLevel", 20));
        builder.saturation(((Number) map.getOrDefault("saturation", 20.0f)).floatValue());
        builder.health(((Number) map.getOrDefault("health", 20.0)).doubleValue());

        @SuppressWarnings("unchecked")
        Map<String, Integer> effects = (Map<String, Integer>) map.get("potionEffects");
        builder.serializedPotionEffects(effects != null ? effects : new HashMap<>());

        builder.worldName((String) map.get("worldName"));
        builder.x(((Number) map.getOrDefault("x", 0.0)).doubleValue());
        builder.y(((Number) map.getOrDefault("y", 0.0)).doubleValue());
        builder.z(((Number) map.getOrDefault("z", 0.0)).doubleValue());
        builder.yaw(((Number) map.getOrDefault("yaw", 0.0f)).floatValue());
        builder.pitch(((Number) map.getOrDefault("pitch", 0.0f)).floatValue());
        builder.gameMode((String) map.getOrDefault("gameMode", "SURVIVAL"));
        builder.allowFlight((Boolean) map.getOrDefault("allowFlight", false));
        builder.isFlying((Boolean) map.getOrDefault("isFlying", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) map.get("metadata");
        builder.metadata(metadataMap != null ? metadataMap : new HashMap<>());

        return builder.build();
    }
}
