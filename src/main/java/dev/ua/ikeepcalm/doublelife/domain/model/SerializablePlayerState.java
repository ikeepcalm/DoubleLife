package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
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
public class SerializablePlayerState implements ConfigurationSerializable {
    
    private String serializedInventory;
    private String serializedArmor;
    private String serializedOffHand;
    private int level;
    private float experience;
    private int foodLevel;
    private float saturation;
    private double health;
    private Map<String, Integer> potionEffects;
    private String worldName;
    private double x, y, z;
    private float yaw, pitch;
    private String gameMode;
    private Map<String, Object> metadata;
    
    public static SerializablePlayerState fromPlayerState(PlayerState playerState) {
        SerializablePlayerStateBuilder builder = SerializablePlayerState.builder();
        
        try {
            // Serialize inventory items
            builder.serializedInventory(serializeItemStackArray(playerState.getInventory()));
            builder.serializedArmor(serializeItemStackArray(playerState.getArmor()));
            builder.serializedOffHand(serializeItemStack(playerState.getOffHand()));
            
            // Copy simple fields
            builder.level(playerState.getLevel());
            builder.experience(playerState.getExperience());
            builder.foodLevel(playerState.getFoodLevel());
            builder.saturation(playerState.getSaturation());
            builder.health(playerState.getHealth());
            builder.gameMode(playerState.getGameMode().name());
            
            // Serialize location
            Location loc = playerState.getLocation();
            if (loc != null && loc.getWorld() != null) {
                builder.worldName(loc.getWorld().getName());
                builder.x(loc.getX());
                builder.y(loc.getY());
                builder.z(loc.getZ());
                builder.yaw(loc.getYaw());
                builder.pitch(loc.getPitch());
            }
            
            // Serialize potion effects
            Map<String, Integer> effects = new HashMap<>();
            if (playerState.getPotionEffects() != null) {
                for (PotionEffect effect : playerState.getPotionEffects()) {
                    effects.put(effect.getType().getName(), effect.getDuration());
                }
            }
            builder.potionEffects(effects);
            
            builder.metadata(playerState.getMetadata() != null ? playerState.getMetadata() : new HashMap<>());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize player state", e);
        }
        
        return builder.build();
    }
    
    public PlayerState toPlayerState() {
        try {
            return PlayerState.builder()
                .inventory(deserializeItemStackArray(serializedInventory))
                .armor(deserializeItemStackArray(serializedArmor))
                .offHand(deserializeItemStack(serializedOffHand))
                .level(level)
                .experience(experience)
                .foodLevel(foodLevel)
                .saturation(saturation)
                .health(health)
                .potionEffects(deserializePotionEffects())
                .location(deserializeLocation())
                .gameMode(GameMode.valueOf(gameMode))
                .metadata(metadata)
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize player state", e);
        }
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
        if (potionEffects != null) {
            for (Map.Entry<String, Integer> entry : potionEffects.entrySet()) {
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
        map.put("potionEffects", potionEffects);
        map.put("worldName", worldName);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", yaw);
        map.put("pitch", pitch);
        map.put("gameMode", gameMode);
        map.put("metadata", metadata);
        
        return map;
    }
    
    public static SerializablePlayerState deserialize(Map<String, Object> map) {
        SerializablePlayerStateBuilder builder = SerializablePlayerState.builder();
        
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
        builder.potionEffects(effects != null ? effects : new HashMap<>());
        
        builder.worldName((String) map.get("worldName"));
        builder.x(((Number) map.getOrDefault("x", 0.0)).doubleValue());
        builder.y(((Number) map.getOrDefault("y", 0.0)).doubleValue());
        builder.z(((Number) map.getOrDefault("z", 0.0)).doubleValue());
        builder.yaw(((Number) map.getOrDefault("yaw", 0.0f)).floatValue());
        builder.pitch(((Number) map.getOrDefault("pitch", 0.0f)).floatValue());
        builder.gameMode((String) map.getOrDefault("gameMode", "SURVIVAL"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) map.get("metadata");
        builder.metadata(metadataMap != null ? metadataMap : new HashMap<>());
        
        return builder.build();
    }
}