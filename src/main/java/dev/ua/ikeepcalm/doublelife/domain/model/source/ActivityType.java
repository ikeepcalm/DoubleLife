package dev.ua.ikeepcalm.doublelife.domain.model.source;

import lombok.Getter;

@Getter
public enum ActivityType {
    COMMAND("Command Executed"),
    GAMEMODE_CHANGE("Gamemode Changed"),
    ITEM_GIVE("Item Given"),
    BLOCK_PLACE("Block Placed"),
    BLOCK_BREAK("Block Broken"),
    CONTAINER_ACCESS("Container Accessed"),
    ITEM_DROP("Item Dropped"),
    ITEM_PICKUP("Item Picked Up"),
    TELEPORT("Teleported"),
    CONTAINER_TRANSFER("Container Transfer"),
    SESSION_START("Session Started"),
    SESSION_END("Session Ended");
    
    private final String displayName;
    
    ActivityType(String displayName) {
        this.displayName = displayName;
    }

}