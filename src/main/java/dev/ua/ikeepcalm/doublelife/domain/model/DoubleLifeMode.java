package dev.ua.ikeepcalm.doublelife.domain.model;

public enum DoubleLifeMode {
    DEFAULT("Default Mode", "Commands only accessible in DoubleLife mode"),
    TURBO("Turbo Mode", "Full admin permissions with command monitoring");
    
    private final String displayName;
    private final String description;
    
    DoubleLifeMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
}