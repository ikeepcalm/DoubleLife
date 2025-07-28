package dev.ua.ikeepcalm.doublelife.domain.model;

import lombok.Builder;
import lombok.Data;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class PlayerState {
    
    private ItemStack[] inventory;
    private ItemStack[] armor;
    private ItemStack offHand;
    private int level;
    private float experience;
    private int foodLevel;
    private float saturation;
    private double health;
    private Collection<PotionEffect> potionEffects;
    private Location location;
    private GameMode gameMode;
    private Map<String, Object> metadata;
    
    public static PlayerState capture(Player player) {
        return PlayerState.builder()
            .inventory(player.getInventory().getStorageContents().clone())
            .armor(player.getInventory().getArmorContents().clone())
            .offHand(player.getInventory().getItemInOffHand().clone())
            .level(player.getLevel())
            .experience(player.getExp())
            .foodLevel(player.getFoodLevel())
            .saturation(player.getSaturation())
            .health(player.getHealth())
            .potionEffects(player.getActivePotionEffects())
            .location(player.getLocation().clone())
            .gameMode(player.getGameMode())
            .metadata(new HashMap<>())
            .build();
    }
    
    public void restore(Player player) {
        player.getInventory().clear();
        player.getInventory().setStorageContents(inventory);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(offHand);
        
        player.setLevel(level);
        player.setExp(experience);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setHealth(health);
        
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        potionEffects.forEach(player::addPotionEffect);
        
        player.teleport(location);
        player.setGameMode(gameMode);
    }
}