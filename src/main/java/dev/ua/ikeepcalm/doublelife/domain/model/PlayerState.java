package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PlayerState implements ConfigurationSerializable {

    private ItemStack[] inventory;
    private ItemStack[] armor;
    private ItemStack offHand;
    private List<PotionEffect> potionEffects;
    private Location location;
    private int level;
    private float experience;
    private int foodLevel;
    private float saturation;
    private double health;
    private String gameMode;
    private boolean allowFlight;
    private boolean isFlying;

    public static PlayerState capture(Player player) {
        return PlayerState.builder()
                .inventory(cloneAll(player.getInventory().getStorageContents()))
                .armor(cloneAll(player.getInventory().getArmorContents()))
                .offHand(player.getInventory().getItemInOffHand().clone())
                .potionEffects(new ArrayList<>(player.getActivePotionEffects()))
                .location(player.getLocation().clone())
                .level(player.getLevel())
                .experience(player.getExp())
                .foodLevel(player.getFoodLevel())
                .saturation(player.getSaturation())
                .health(player.getHealth())
                .gameMode(player.getGameMode().name())
                .allowFlight(player.getAllowFlight())
                .isFlying(player.isFlying())
                .build();
    }

    /**
     * @return false if the teleport back failed (e.g. player is disconnecting), so the caller can retry on join
     */
    public boolean restore(Player player) {
        player.getInventory().clear();
        player.getInventory().setStorageContents(inventory);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(offHand);

        player.setGameMode(GameMode.valueOf(gameMode));
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && isFlying);
        player.setLevel(level);
        player.setExp(experience);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        if (!player.isDead()) {
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(Math.max(1, Math.min(health, maxHealth != null ? maxHealth.getValue() : 20)));
        }

        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(potionEffects);

        return location == null || location.getWorld() == null || (!player.isDead() && player.teleport(location));
    }

    private static ItemStack[] cloneAll(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    private static String encode(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decode(Object data) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode((String) data));
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("inventory", encode(inventory));
        map.put("armor", encode(armor));
        map.put("offHand", encode(new ItemStack[]{offHand}));
        map.put("potionEffects", potionEffects);
        map.put("location", location);
        map.put("level", level);
        map.put("experience", experience);
        map.put("foodLevel", foodLevel);
        map.put("saturation", saturation);
        map.put("health", health);
        map.put("gameMode", gameMode);
        map.put("allowFlight", allowFlight);
        map.put("isFlying", isFlying);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static PlayerState deserialize(Map<String, Object> map) {
        return PlayerState.builder()
                .inventory(decode(map.get("inventory")))
                .armor(decode(map.get("armor")))
                .offHand(decode(map.get("offHand"))[0])
                .potionEffects((List<PotionEffect>) map.getOrDefault("potionEffects", new ArrayList<>()))
                .location((Location) map.get("location"))
                .level(((Number) map.getOrDefault("level", 0)).intValue())
                .experience(((Number) map.getOrDefault("experience", 0)).floatValue())
                .foodLevel(((Number) map.getOrDefault("foodLevel", 20)).intValue())
                .saturation(((Number) map.getOrDefault("saturation", 5)).floatValue())
                .health(((Number) map.getOrDefault("health", 20)).doubleValue())
                .gameMode((String) map.getOrDefault("gameMode", "SURVIVAL"))
                .allowFlight((Boolean) map.getOrDefault("allowFlight", false))
                .isFlying((Boolean) map.getOrDefault("isFlying", false))
                .build();
    }
}
